package org.windy.playershelter.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.ReturnPoint;
import org.windy.playershelter.domain.model.Shelter;

/**
 * 把玩家送进某个庇护所——单服直接传送，跨服先写交接待办再让代理换服（决策 #57/#59）。
 *
 * <p>本服承载（{@link PsCore#isLocal})：异步确保世界加载（地形 provider 走异步管线）→ 安全落点传送。
 * 他服承载：写 {@code ps_pending}（到站消费）→ {@link org.windy.playershelter.adapter.proxy.ServerTransfer}
 * 经 BungeeCord 通道把人 Connect 过去。目标后端在 join 时由 {@link org.windy.playershelter.runtime.listener.LifecycleListener}
 * 读出待办、把人传进对应庇护所。
 *
 * <p>同时负责「离开家园」的往返记忆（{@code /ps leave}）：进门前把玩家原来的位置记进
 * {@code ps_return_point}，离开时按原路送回（可能是主世界 / 下界 / 末地，也可能在别的后端服）。
 */
public final class ShelterRouter {

    /** 主世界名后缀判断用：庇护所世界以 {@code shelter_} 开头（决策 #52）。 */
    private static final String SHELTER_WORLD_PREFIX = "shelter_";

    private final PsCore core;

    public ShelterRouter(PsCore core) {
        this.core = core;
    }

    /**
     * 送玩家进目标庇护所。
     *
     * @param player  在线玩家
     * @param target  目标庇护所
     * @param failMsg 失败时给玩家的提示（世界加载失败/跨服不可达）
     */
    public void send(Player player, Shelter target, String failMsg) {
        recordDeparture(player);
        if (core.isLocal(target.serverName())) {
            sendLocal(player, target, failMsg);
            return;
        }
        // 跨服：写待办 + 让代理换服。到站后由目标后端消费待办进世界。
        core.pending().save(PlayerRef.of(player.getUniqueId()), target.owner());
        Messages.infoKey(player, "router.cross-server", "正在把你转送到目标服务器…");
        core.transfer().connect(player, target.serverName());
    }

    /** 本服承载：确保世界加载后安全落点传送。 */
    public void sendLocal(Player player, Shelter target, String failMsg) {
        core.world().ensureWorldAsync(target, player.getUniqueId(), ready -> {
            World w = Bukkit.getWorld(ready.worldName());
            if (w == null) {
                Messages.error(player, failMsg);
                return;
            }
            core.world().applyBorder(ready);
            Location spawn = w.getSpawnLocation();
            core.safeLanding().prepareAsync(core.plugin(), spawn).whenComplete((landing, error) -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null || landing == null) {
                    Messages.error(player, failMsg);
                    return;
                }
                player.teleportAsync(landing).thenAccept(ok -> {
                    if (!ok && player.isOnline()) {
                        Messages.error(player, failMsg);
                    }
                });
            });
        }, () -> Messages.error(player, failMsg));
    }

    // —— /ps leave：离开家园，回到进门前的那个位置 ——

    /**
     * 记下玩家「进门前」的位置，供 {@code /ps leave} 原路送回。
     *
     * <p>只在玩家<b>当前不在任何庇护所世界</b>时记录：玩家从 A 家串门到 B 家时不覆盖，
     * 这样 B 家 {@code /ps leave} 回的是最初的主世界/下界/末地，而不是 A 家。
     */
    public void recordDeparture(Player player) {
        if (player.getWorld().getName().startsWith(SHELTER_WORLD_PREFIX)) {
            return;
        }
        Location loc = player.getLocation();
        core.returnPoints().save(ReturnPoint.of(
                PlayerRef.of(player.getUniqueId()),
                loc.getWorld().getName(),
                core.config().defaultServerName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()));
    }

    /**
     * {@code /ps leave}：把玩家送回进门前的落脚点。
     *
     * <p>三种落法：
     * <ol>
     *   <li>有记录、且那个世界在本服（单服或同后端）→ 直接传送回原坐标。</li>
     *   <li>有记录、但那个世界在别的后端服（跨服）→ 标记 pending 并发车，到站后由
     *       {@link org.windy.playershelter.runtime.listener.LifecycleListener} 消费落点。</li>
     *   <li>没记录 / 世界已不存在 / 记录指向庇护所世界 → 回本服主世界出生点。</li>
     * </ol>
     */
    public void leave(Player player, String failMsg) {
        String currentWorld = player.getWorld().getName();
        if (!currentWorld.startsWith(SHELTER_WORLD_PREFIX)) {
            Messages.warnKey(player, "leave.not-in-shelter", "你现在不在自己的家园里，无需离开。");
            return;
        }
        ReturnPoint point = core.returnPoints().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (point == null) {
            Messages.infoKey(player, "leave.no-record", "没有记录到你进入家园前的位置，正在送你回主世界出生点…");
            sendToMainSpawn(player, failMsg);
            return;
        }
        if (point.worldName().startsWith(SHELTER_WORLD_PREFIX)) {
            // 记录指向庇护所世界（历史脏数据）→ 视为无效，回主世界。
            Messages.infoKey(player, "leave.no-record", "没有记录到你进入家园前的位置，正在送你回主世界出生点…");
            core.returnPoints().delete(PlayerRef.of(player.getUniqueId()));
            sendToMainSpawn(player, failMsg);
            return;
        }
        String pointServer = point.serverName() == null ? "" : point.serverName();
        boolean pointLocal = pointServer.isBlank()
                || !core.crossServer()
                || core.isLocal(pointServer);
        if (!pointLocal) {
            // 落脚点在别的后端服 → 先记成「已发车」，让目标后端在 join 时把人放回原坐标。
            core.returnPoints().save(point.withPendingArrival(true));
            Messages.infoKey(player, "leave.cross-server", "正在把你送回原来的服务器…");
            core.transfer().connect(player, pointServer);
            return;
        }
        Messages.infoKey(player, "leave.teleporting", "「归去来兮，田园将芜胡不归」正在送你回原来的位置…");
        teleportBack(player, point, failMsg, true);
    }

    /**
     * 跨服到站后把玩家放回记录里的坐标（由 {@code LifecycleListener} 在 join 时调用）。
     * 消费成功后删除记录。
     */
    public void completePendingArrival(Player player, String failMsg) {
        PlayerRef ref = PlayerRef.of(player.getUniqueId());
        core.returnPoints().find(ref).ifPresent(point -> {
            if (!point.pendingArrival()) {
                return;
            }
            // 只认「发往本服」的记录；若发车目标不是本服（代理把玩家送到了别处），保持原样。
            String pointServer = point.serverName() == null ? "" : point.serverName();
            String localServer = core.config().defaultServerName();
            if (!pointServer.isBlank() && !pointServer.equalsIgnoreCase(localServer)) {
                return;
            }
            core.returnPoints().delete(ref);
            if (point.worldName().startsWith(SHELTER_WORLD_PREFIX)) {
                sendToMainSpawn(player, failMsg);
                return;
            }
            Messages.infoKey(player, "leave.teleporting", "正在送你回原来的位置…");
            teleportBack(player, point, failMsg, false);
        });
    }

    /**
     * 传送到记录里的坐标；{@code consume} 为 true 时成功后删除记录（本服直接离开那条路径）。
     *
     * <p>这里<b>不走安全落点</b>：记录的位置是玩家离开前的真实脚下（很可能已经登在空中或水里），
     * 走安全落点会为了「别掉下去」而往主世界/下界硬塞一格玻璃方块——那是改别人的地图，不该发生。
     * 直接传送到原坐标，物理引擎自然处理下落。
     */
    private void teleportBack(Player player, ReturnPoint point, String failMsg, boolean consume) {
        World world = Bukkit.getWorld(point.worldName());
        if (world == null) {
            Messages.warnKey(player, "leave.world-gone",
                    "你原来所在的世界已经无法加载，正在送你回主世界出生点…");
            if (consume) {
                core.returnPoints().delete(PlayerRef.of(player.getUniqueId()));
            }
            sendToMainSpawn(player, failMsg);
            return;
        }
        Location target = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        player.teleportAsync(target).thenAccept(ok -> {
            if (!player.isOnline()) {
                return;
            }
            if (!ok) {
                Messages.error(player, failMsg);
                return;
            }
            if (consume) {
                core.returnPoints().delete(PlayerRef.of(player.getUniqueId()));
            }
            Messages.okKey(player, "leave.success", "已离开家园，回到你原来的位置。");
        });
    }

    /** 兜底：回本服主世界（{@code Bukkit.getWorlds().get(0)}）出生点。 */
    private void sendToMainSpawn(Player player, String failMsg) {
        if (Bukkit.getWorlds().isEmpty()) {
            Messages.error(player, failMsg);
            return;
        }
        World main = Bukkit.getWorlds().get(0);
        Location spawn = main.getSpawnLocation();
        core.safeLanding().prepareAsync(core.plugin(), spawn).whenComplete((landing, error) -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null || landing == null) {
                Messages.error(player, failMsg);
                return;
            }
            player.teleportAsync(landing).thenAccept(ok -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!ok) {
                    Messages.error(player, failMsg);
                    return;
                }
                core.returnPoints().delete(PlayerRef.of(player.getUniqueId()));
                Messages.okKey(player, "leave.success-main-spawn", "已离开家园，回到主世界出生点。");
            });
        });
    }
}

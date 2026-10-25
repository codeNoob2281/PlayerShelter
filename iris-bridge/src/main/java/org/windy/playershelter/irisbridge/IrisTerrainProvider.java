package org.windy.playershelter.irisbridge;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.util.plugin.VolmitSender;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.playershelter.api.terrain.TerrainProvider;
import org.windy.playershelter.api.terrain.TerrainRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 用 Iris 生成庇护所 NATURAL 世界（原主插件 {@code IrisSupport} 的逻辑，迁到这里）。
 *
 * <p>目标 Iris 版本：<b>3.9.x</b>（包名 {@code com.volmit.iris.*}，支持 MC 1.20.1 ~ 1.21.11）。
 * 注意 Iris 4.x 改名为 {@code art.arcane.iris.*} 且只支持 MC 26.x，与本模块不兼容。
 *
 * <p><b>只认领 NATURAL</b>：主插件仅在 {@code GenerationType.NATURAL} 时询问 provider，
 * FLAT/VOID 永远走原版。
 *
 * <p><b>线程</b>：Iris 的 {@code create()} 必须在异步线程调（其内部自行 submit 回主线程），
 * 故这里返回的 future 在异步线程完成；主插件负责切回主线程做出生点/边界。
 */
final class IrisTerrainProvider implements TerrainProvider {

    private final Plugin plugin;
    private final String dimension;

    IrisTerrainProvider(Plugin plugin, String dimension) {
        this.plugin = plugin;
        this.dimension = dimension == null || dimension.isBlank() ? "overworld" : dimension;
    }

    @Override
    public String id() {
        return "iris";
    }

    /** 数值小的先问：Iris 想压过其它通用 provider 就给负数。 */
    @Override
    public int priority() {
        return -10;
    }

    @Override
    public boolean supports(TerrainRequest request) {
        // 只接管自然地形；FLAT/VOID 一律留给主插件原版生成。
        return request != null && "natural".equalsIgnoreCase(request.genType());
    }

    @Override
    public CompletableFuture<World> generateAsync(TerrainRequest request) {
        CompletableFuture<World> future = new CompletableFuture<>();
        CommandSender audience = resolveAudience(request);
        String worldName = request.worldName();
        plugin.getLogger().info("[IrisBridge] " + (request.fresh() ? "创建" : "重载")
                + " Iris 世界(维度 '" + dimension + "'): " + worldName
                + (audience instanceof Player p ? "（进度→" + p.getName() + "）" : ""));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var creator = IrisToolbelt.createWorld()
                        .name(worldName)
                        .dimension(dimension)
                        .seed(request.seed());
                if (audience != null) {
                    creator = creator.sender(new VolmitSender(audience));
                }
                World world = creator.create();
                future.complete(world);
            } catch (Throwable t) {
                future.completeExceptionally(new IllegalStateException(
                        "Iris 建世界失败(world=" + worldName + ", dimension=" + dimension + "): " + t, t));
            }
        });
        return future;
    }

    @Override
    public boolean lazilyGenerated(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world != null && IrisToolbelt.isIrisWorld(world);
    }

    private CommandSender resolveAudience(TerrainRequest request) {
        if (request.audience() == null) {
            return null;
        }
        return Bukkit.getPlayer(request.audience());
    }
}

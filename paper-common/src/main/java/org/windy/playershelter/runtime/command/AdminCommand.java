package org.windy.playershelter.runtime.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.windy.playershelter.api.event.ShelterDeleteEvent;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;
import org.windy.playershelter.service.EntityLimits;
import org.windy.playershelter.service.ShelterLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员子命令（决策 #61 全套 /ps admin / #72 转让仅管理员）。
 * 入口：{@code /ps admin <action> [玩家] [参数]}，需 {@code playershelter.admin} 权限。
 */
public final class AdminCommand {

    private static final List<String> ACTIONS = Arrays.asList(
            "tp", "delete", "setlevel", "setorigin", "origin", "info", "list", "unload", "transfer",
            "regen", "grant", "disk", "perf", "reload", "limit");

    private final PsCore core;

    public AdminCommand(PsCore core) {
        this.core = core;
    }

    static boolean isAdminAction(String s) {
        return ACTIONS.contains(s.toLowerCase());
    }

    static List<String> actions() {
        return ACTIONS;
    }

    void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playershelter.admin")) {
            Messages.errorKeyOnly(sender, "admin.no-permission");
            return;
        }
        String action = args[0].toLowerCase();
        switch (action) {
            case "tp" -> tp(sender, args);
            case "delete" -> delete(sender, args);
            case "setlevel" -> setlevel(sender, args);
            case "setorigin", "origin" -> setorigin(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender);
            case "unload" -> unload(sender, args);
            case "transfer" -> transfer(sender, args);
            case "regen" -> regen(sender, args);
            case "grant" -> grant(sender, args);
            case "disk" -> disk(sender);
            case "perf" -> perf(sender);
            case "reload" -> reload(sender);
            case "limit" -> limit(sender, args);
            default -> Messages.errorKeyOnly(sender, "admin.unknown-action", "action", action);
        }
    }

    private Shelter target(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.warnKeyOnly(sender, "admin.target-usage", "action", args[0]);
            return null;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
        Shelter s = core.repo().find(PlayerRef.of(op.getUniqueId())).orElse(null);
        if (s == null) {
            Messages.errorKeyOnly(sender, "admin.target-no-shelter", "player", args[1]);
        }
        return s;
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Messages.errorKeyOnly(sender, "admin.tp-player-only");
            return;
        }
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        // 经路由：本服直接传送，他服承载则跨服换服（避免 ensureWorldAsync 在本服误建同名重复世界）。
        new org.windy.playershelter.runtime.ShelterRouter(core).send(p, s, Messages.getKey("world-load-failed"));
        Messages.okKeyOnly(p, "admin.tp-start", "player", args[1]);
    }

    private void delete(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new ShelterDeleteEvent(
                s.owner().uuid(), s.worldName(), ShelterDeleteEvent.Reason.ADMIN));
        core.world().deleteWorld(s.worldName());
        core.repo().delete(s.owner());
        core.tags().clear(s.owner());   // 决策 P3：连带清标签/留言
        core.board().clear(s.owner());
        Messages.okKeyOnly(sender, "admin.delete-success", "player", args[1]);
    }

    private void setlevel(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        if (args.length < 3) {
            Messages.warnKeyOnly(sender, "admin.setlevel-usage");
            return;
        }
        int lvl;
        try {
            lvl = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            Messages.errorKeyOnly(sender, "admin.level-number");
            return;
        }
        lvl = Math.max(1, Math.min(lvl, s.layout().maxLevel()));
        Shelter up = s.withLevel(lvl)
                .withLimits(ShelterLimits.migrate(s.limits(), s.level(), lvl,
                        core.limits().entityLimits(), core.config()))
                .withAutoUnlockedChunksForLevel();
        core.repo().save(up);
        if (core.world().isLoaded(up.worldName())) {
            core.world().applyBorder(up);
        }
        Messages.okKeyOnly(sender, "admin.setlevel-success",
                "player", args[1], "level", lvl, "chunks", up.sideChunks());
    }

    private void setorigin(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        if (args.length < 3) {
            Messages.warnKeyOnly(sender, "admin.setorigin-usage");
            return;
        }
        int x;
        int z;
        if (args[2].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player p)) {
                Messages.errorKeyOnly(sender, "admin.setorigin-here-player-only");
                return;
            }
            x = p.getLocation().getBlockX();
            z = p.getLocation().getBlockZ();
        } else {
            if (args.length < 4) {
                Messages.warnKeyOnly(sender, "admin.setorigin-usage");
                return;
            }
            try {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                Messages.errorKeyOnly(sender, "admin.origin-number");
                return;
            }
        }

        int originX = snapChunkOrigin(x);
        int originZ = snapChunkOrigin(z);
        ShelterLayout old = s.layout();
        ShelterLayout layout = new ShelterLayout(old.initialChunks(), old.maxChunks(), old.maxLevel(),
                old.sortedSideLevels(), originX, originZ);
        Shelter moved = new Shelter(s.owner(), s.worldName(), s.seed(), s.genType(), s.level(), layout,
                s.visibility(), s.admins(), s.trusted(), s.access(), s.denied(), s.flags(), s.bulletin(),
                s.serverName(), s.createdAt(), s.lastActive(), s.likes(), s.limits());

        core.repo().save(moved);
        if (core.world().isLoaded(moved.worldName())) {
            core.world().applyBorder(moved);
        }
        Messages.okKeyOnly(sender, "admin.setorigin-success",
                "player", args[1],
                "x", originX, "z", originZ,
                "cx", moved.layout().borderCenterXAtLevel(moved.level()),
                "cz", moved.layout().borderCenterZAtLevel(moved.level()));
    }

    private void info(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        Messages.plainKeyOnly(sender, "admin.info.header", "player", args[1]);
        Messages.plainKeyOnly(sender, "admin.info.world",
                "world", s.worldName(), "type", s.genType(), "level", s.level());
        Messages.plainKeyOnly(sender, "admin.info.border",
                "plot", s.sideChunks() + "x" + s.sideChunks(),
                "blocks", s.borderSize(), "area", s.areaChunks(),
                "visibility", s.visibility(), "likes", s.likes());
        Messages.plainKeyOnly(sender, "admin.info.origin",
                "x", s.layout().originX(), "z", s.layout().originZ(),
                "cx", s.layout().borderCenterXAtLevel(s.level()),
                "cz", s.layout().borderCenterZAtLevel(s.level()));
        Messages.plainKeyOnly(sender, "admin.info.loaded",
                "loaded", core.world().isLoaded(s.worldName()), "online", core.world().playerCount(s.worldName()));
    }

    private void list(CommandSender sender) {
        int total = core.repo().all().size();
        int loaded = core.world().loadedShelterWorlds().size();
        Messages.plainKeyOnly(sender, "admin.list.header");
        Messages.plainKeyOnly(sender, "admin.list.summary",
                "total", total, "loaded", loaded, "max", core.config().maxLoadedWorlds());
        for (String w : core.world().loadedShelterWorlds()) {
            Messages.plainKeyOnly(sender, "admin.list.world-row",
                    "world", w, "online", core.world().playerCount(w));
        }
    }

    private void unload(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        boolean ok = core.world().unload(s.worldName());
        if (ok) {
            Messages.infoKeyOnly(sender, "admin.unload-success");
        } else {
            Messages.infoKeyOnly(sender, "admin.unload-failed");
        }
    }

    /** 转让所有权（决策 #72 仅管理员）：/ps admin transfer <原主> <新主>。 */
    private void transfer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Messages.warnKeyOnly(sender, "admin.transfer-usage");
            return;
        }
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        OfflinePlayer newOwner = Bukkit.getOfflinePlayer(args[2]);
        if (core.repo().find(PlayerRef.of(newOwner.getUniqueId())).isPresent()) {
            Messages.errorKeyOnly(sender, "admin.transfer-target-has-shelter",
                    "player", args[2]);
            return;
        }
        // 删旧记录，按新主重建记录（世界文件名仍随旧 owner uuid，避免移动存档；只换归属）。
        Shelter moved = new Shelter(PlayerRef.of(newOwner.getUniqueId()), s.worldName(), s.seed(), s.genType(),
                s.level(), s.layout(), s.visibility(), s.admins(), s.trusted(), s.access(), s.denied(),
                s.flags(), s.bulletin(), s.serverName(), s.createdAt(), s.lastActive(), s.likes(), s.limits());
        core.repo().delete(s.owner());
        core.repo().save(moved);
        // 转让后旧 owner uuid 的标签/留言成孤儿，清掉（新主重新打标签）。
        core.tags().clear(s.owner());
        core.board().clear(s.owner());
        Messages.okKeyOnly(sender, "admin.transfer-success", "from", args[1], "to", args[2]);
    }

    /** 强制重建某人世界（决策 P4，处理损坏世界）：抹地形重生成，保留等级/成员/标签。走 service 共享逻辑。 */
    private void regen(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        Player audience = sender instanceof Player p ? p : null;
        java.util.UUID au = audience == null ? null : audience.getUniqueId();
        Messages.infoKeyOnly(sender, "admin.regen-start", "player", args[1]);
        core.shelters().adminRegen(s.owner(), au,
                ready -> Messages.okKeyOnly(sender, "admin.regen-success", "player", args[1]),
                () -> Messages.errorKeyOnly(sender, "admin.regen-failed"));
    }

    /** 给/扣等级（决策 P4）：/ps admin grant <玩家> <±等级增量>。 */
    private void grant(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        if (args.length < 3) {
            Messages.warnKeyOnly(sender, "admin.grant-usage");
            return;
        }
        int delta;
        try {
            delta = Integer.parseInt(args[2].replace("+", ""));
        } catch (NumberFormatException e) {
            Messages.errorKeyOnly(sender, "admin.delta-number");
            return;
        }
        int lvl = Math.max(1, Math.min(s.level() + delta, s.layout().maxLevel()));
        Shelter up = s.withLevel(lvl)
                .withLimits(ShelterLimits.migrate(s.limits(), s.level(), lvl,
                        core.limits().entityLimits(), core.config()))
                .withAutoUnlockedChunksForLevel();
        core.repo().save(up);
        if (core.world().isLoaded(up.worldName())) {
            core.world().applyBorder(up);
        }
        Messages.okKeyOnly(sender, "admin.grant-success",
                "player", args[1], "level", lvl, "chunks", up.sideChunks());
    }

    /** 限额键 tab 补全候选（固定六项；machine:<id> 前缀由玩家自行输入）。 */
    static List<String> limitKeys() {
        return List.of(ShelterLimits.MOBS, ShelterLimits.TILES, ShelterLimits.DROPS,
                ShelterLimits.VEHICLES, ShelterLimits.ADMIN, ShelterLimits.TRUST);
    }

    /**
     * 单条限额调整（限额数值入库）：/ps admin limit <玩家> <键> <set|add> <值>。
     * 键 = mobs|tiles|drops|vehicles|admin|trust|machine:<方块id>；只改这一个庇护所，不影响 levels.yml 默认值。
     */
    private void limit(CommandSender sender, String[] args) {
        Shelter s = target(sender, args);
        if (s == null) {
            return;
        }
        if (args.length < 5) {
            Messages.warnKeyOnly(sender, "admin.limit-usage");
            return;
        }
        String key = args[2].toLowerCase();
        if (!ShelterLimits.isValidKey(key)) {
            Messages.errorKeyOnly(sender, "admin.limit-unknown-key", "key", args[2]);
            return;
        }
        String mode = args[3].toLowerCase();
        if (!mode.equals("set") && !mode.equals("add")) {
            Messages.warnKeyOnly(sender, "admin.limit-usage");
            return;
        }
        boolean add = mode.equals("add");
        int value;
        try {
            value = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            Messages.errorKeyOnly(sender, "admin.limit-number");
            return;
        }
        Map<String, Integer> limits = new HashMap<>(s.limits());
        // add 以当前生效值为基线（库存缺键时回退配置按级现算值）。
        int result = Math.max(0, add ? currentLimit(s, key) + value : value);
        limits.put(key, result);
        core.repo().save(s.withLimits(limits));
        Messages.okKeyOnly(sender, "admin.limit-success",
                "player", args[1], "key", key, "value", result);
    }

    /** 某项限额的当前生效值：库存值优先，缺键回退 levels.yml 按级配置。 */
    private int currentLimit(Shelter s, String key) {
        Integer stored = s.limits().get(key);
        if (stored != null) {
            return stored;
        }
        EntityLimits el = core.limits().entityLimits();
        return switch (key) {
            case ShelterLimits.MOBS -> el.mobCap(s.level());
            case ShelterLimits.TILES -> el.tileCap(s.level());
            case ShelterLimits.DROPS -> el.dropCap(s.level());
            case ShelterLimits.VEHICLES -> el.vehicleCap(s.level());
            case ShelterLimits.ADMIN -> core.config().adminCapAt(s.level());
            case ShelterLimits.TRUST -> core.config().trustCapAt(s.level());
            default -> key.startsWith(ShelterLimits.MACHINE_PREFIX)
                    ? Math.max(0, el.machineCap(key.substring(ShelterLimits.MACHINE_PREFIX.length()), s.level()))
                    : 0;
        };
    }

    /** 磁盘占用榜（决策 P4）：列世界存档大小 + 最后活跃，方便清理。异步扫盘避免卡主线程。 */
    private void disk(CommandSender sender) {
        Messages.infoKeyOnly(sender, "admin.disk-start");
        Bukkit.getScheduler().runTaskAsynchronously(core.plugin(), () -> {
            List<Shelter> all = core.repo().all();
            List<String> lines = new ArrayList<>();
            long total = 0;
            List<Object[]> rows = new ArrayList<>(); // [name, sizeBytes, lastActive]
            for (Shelter s : all) {
                long size = folderSize(s.worldName());
                total += size;
                rows.add(new Object[]{s.worldName(), size, s.lastActive().toEpochMilli()});
            }
            rows.sort((a, b) -> Long.compare((long) b[1], (long) a[1])); // 大到小
            long grand = total;
            int show = Math.min(15, rows.size());
            for (int i = 0; i < show; i++) {
                Object[] r = rows.get(i);
                long days = (System.currentTimeMillis() - (long) r[2]) / 86400_000L;
                lines.add(Messages.formatKey("admin.disk.row",
                        "world", r[0], "size", humanSize((long) r[1]), "days", days));
            }
            Bukkit.getScheduler().runTask(core.plugin(), () -> {
                Messages.plainKeyOnly(sender, "admin.disk.header",
                        "count", all.size(), "size", humanSize(grand), "show", show);
                lines.forEach(l -> Messages.plain(sender, l));
            });
        });
    }

    /**
     * 当前已加载庇护所世界的运行时负载概览。只读已加载世界，不加载新世界、不卸载区块。
     */
    private void perf(CommandSender sender) {
        List<PerfRow> rows = new ArrayList<>();
        for (String worldName : core.world().loadedShelterWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            org.bukkit.Chunk[] chunks = world.getLoadedChunks();
            int tileEntities = 0;
            int forceLoaded = 0;
            int pluginTickets = 0;
            for (org.bukkit.Chunk chunk : chunks) {
                tileEntities += chunk.getTileEntities().length;
                if (world.isChunkForceLoaded(chunk.getX(), chunk.getZ())) {
                    forceLoaded++;
                }
                pluginTickets += world.getPluginChunkTickets(chunk.getX(), chunk.getZ()).size();
            }
            rows.add(new PerfRow(worldName, world.getPlayers().size(), chunks.length,
                    world.getEntities().size(), tileEntities, forceLoaded, pluginTickets));
        }
        rows.sort((a, b) -> Integer.compare(b.loadedChunks(), a.loadedChunks()));
        int show = Math.min(10, rows.size());
        Messages.plainKeyOnly(sender, "admin.perf.header", "loaded", rows.size(), "show", show);
        if (rows.isEmpty()) {
            Messages.infoKeyOnly(sender, "admin.perf.empty");
            return;
        }
        for (int i = 0; i < show; i++) {
            PerfRow row = rows.get(i);
            Messages.plainKeyOnly(sender, "admin.perf.row",
                    "world", row.worldName(),
                    "players", row.players(),
                    "chunks", row.loadedChunks(),
                    "entities", row.entities(),
                    "tiles", row.tileEntities(),
                    "forced", row.forceLoadedChunks(),
                    "tickets", row.pluginTickets());
        }
        Messages.infoKeyOnly(sender, "admin.perf.note");
    }

    /** 有限热重载（决策 P4）：重读 config.yml/lang/*.yml + 提示需重启的部分。 */
    private void reload(CommandSender sender) {
        core.plugin().reloadConfig();
        Messages.reload(core.plugin());
        Messages.okKeyOnly(sender, "admin.reload-success");
        Messages.warnKeyOnly(sender, "admin.reload-storage-warning");
        Messages.warnKeyOnly(sender, "admin.reload-runtime-warning");
    }

    private long folderSize(String worldName) {
        // 候选：混合端 dimensions 目录 + 经典根目录。
        java.io.File container = Bukkit.getWorldContainer();
        String main = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        for (java.io.File f : new java.io.File[]{
                new java.io.File(container, main + "/dimensions/minecraft/" + worldName),
                new java.io.File(container, worldName)}) {
            if (f.isDirectory()) {
                return dirBytes(f.toPath());
            }
        }
        return 0;
    }

    private long dirBytes(java.nio.file.Path path) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(path)) {
            return walk.filter(java.nio.file.Files::isRegularFile).mapToLong(p -> {
                try {
                    return java.nio.file.Files.size(p);
                } catch (java.io.IOException e) {
                    return 0;
                }
            }).sum();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private int snapChunkOrigin(int coordinate) {
        return Math.floorDiv(coordinate, 16) * 16;
    }

    private String humanSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format("%.1fG", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format("%.1fM", bytes / (double) (1L << 20));
        }
        return String.format("%.0fK", bytes / (double) (1L << 10));
    }

    private record PerfRow(String worldName, int players, int loadedChunks, int entities,
                           int tileEntities, int forceLoadedChunks, int pluginTickets) {
    }
}

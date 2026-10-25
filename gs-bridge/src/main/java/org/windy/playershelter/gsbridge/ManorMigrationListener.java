package org.windy.playershelter.gsbridge;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.windy.guildshelter.api.GuildShelterAPI;
import org.windy.guildshelter.api.GuildShelterMigrationRegion;
import org.windy.guildshelter.api.event.ManorAssignedEvent;
import org.windy.playershelter.api.PlayerShelterApi;
import org.windy.playershelter.api.PlayerShelterMigrationRegion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 软集成：玩家获得 GuildShelter 庄园 slot 时，自动将其 PlayerShelter 庇护所建筑迁移至庄园。
 *
 * <p>与主插件的关系：<b>只经 {@link PlayerShelterApi} 公开接口</b>（区域导出 / 异步复制 / 世界目录 / 删除收尾），
 * 不触碰 {@code PsCore} 之类内部实现——所以本 jar 与主插件可以各自独立升级。
 * 与 GuildShelter 的关系：编译期只依赖其纯接口 API 模块，运行期由 GuildShelter 主插件提供。
 *
 * <p>迁移路径与 {@code /psgs ps-to-gs} 手动命令一致：经主插件 {@code RegionMover} 复制
 * （NeoForge 上逐 chunk 分 tick，Bukkit 上走 WorldEdit clipboard），含 bossbar 进度 + 备份。
 */
public final class ManorMigrationListener implements Listener {

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final PlayerShelterApi psApi;
    private final Logger logger;
    private final String prefix;

    ManorMigrationListener(Plugin plugin, PlayerShelterApi psApi, String prefix) {
        this.plugin = plugin;
        this.psApi = psApi;
        this.logger = plugin.getLogger();
        this.prefix = prefix == null || prefix.isBlank() ? "[PSGS]" : prefix;
    }

    @EventHandler
    public void onManorAssigned(ManorAssignedEvent event) {
        UUID playerId = event.player();
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        // 预检（主线程，快速）
        if (!psApi.hasShelter(playerId)) {
            return;
        }

        GuildShelterAPI gsApi = getGuildShelterApi();
        if (gsApi == null) {
            return;
        }

        PlayerShelterMigrationRegion psRegion = psApi.exportRegion(playerId).orElse(null);
        if (psRegion == null) {
            return;
        }

        Optional<GuildShelterMigrationRegion> gsOpt = gsApi.exportManor(playerId);
        if (gsOpt.isEmpty()) {
            logger.info("[PSGS] 玩家 " + player.getName() + " 的 GS 庄园区域未就绪，跳过迁移。");
            return;
        }
        GuildShelterMigrationRegion gsRegion = gsOpt.get();

        int copySize = Math.min(psRegion.sideChunks(), gsRegion.sideChunks());
        if (copySize <= 0) {
            return;
        }

        String guildId = event.guild().id();
        String migrationId = "auto-" + playerId.toString().substring(0, 8)
                + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);

        logger.info("[PSGS] 开始迁移 " + player.getName() + " 的庇护所 → 公会 " + guildId
                + " 庄园 #" + gsRegion.slot() + "（" + copySize + " chunks）id=" + migrationId);

        // BossBar（玩家在线才显示）
        BossBar bossBar = Bukkit.createBossBar(
                prefix + " 迁移准备中 " + migrationId, BarColor.BLUE, BarStyle.SEGMENTED_20);
        bossBar.setProgress(0.0);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);

        // 与 /psgs ps-to-gs 相同的复制路径
        psApi.copyMigrationRegionAsync(
                psRegion.worldName(), psRegion.minChunkX(), psRegion.minChunkZ(), copySize,
                gsRegion.worldName(), gsRegion.minChunkX(), gsRegion.minChunkZ(),
                step -> runMain(() -> {
                    bossBar.setProgress(Math.max(0.0, Math.min(1.0, step.overall())));
                    bossBar.setTitle(prefix + " " + step.phase() + " " + Math.round(step.overall() * 100) + "%");
                }),
                copied -> runMain(() -> {
                    if (!copied) {
                        logger.warning("[PSGS] 方块复制失败 id=" + migrationId);
                        bossBar.setColor(BarColor.RED);
                        bossBar.setTitle(prefix + " 复制失败");
                        Bukkit.getScheduler().runTaskLater(plugin, bossBar::removeAll, 100L);
                        player.sendMessage("§e[PlayerShelter] 建筑复制失败，你的庇护所已保留。");
                        return;
                    }
                    bossBar.setTitle(prefix + " 备份中...");
                    // 备份 + 删除（异步zip → 主线程删世界）
                    backupAndDelete(player, psRegion.worldName(), gsRegion.slot(), migrationId, bossBar);
                }));
    }

    private void backupAndDelete(Player player, String worldName, int slot,
                                 String migrationId, BossBar bossBar) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // 保存世界
        World loadedWorld = Bukkit.getWorld(worldName);
        if (loadedWorld != null) {
            try { loadedWorld.save(); } catch (RuntimeException ignored) {}
        }

        Optional<Path> sourceFolder = psApi.shelterWorldFolder(playerId);
        if (sourceFolder.isEmpty()) {
            logger.warning("[PSGS] 备份跳过 id=" + migrationId + " 原因=找不到世界目录");
            bossBar.setColor(BarColor.GREEN);
            bossBar.setTitle(prefix + " 迁移完成（未备份）");
            Bukkit.getScheduler().runTaskLater(plugin, bossBar::removeAll, 60L);
            psApi.deleteShelter(playerId);
            player.sendMessage("§a[PlayerShelter] 你的庇护所已迁移到公会庄园（slot " + slot + "）！");
            return;
        }

        Path backupDir = psApi.dataFolder().resolve("backup");
        Path targetZip = uniqueBackupZip(backupDir, playerName, worldName);
        logger.info("[PSGS] 备份开始 id=" + migrationId + " source=" + sourceFolder.get() + " backup=" + targetZip);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean backedUp = zipDirectory(sourceFolder.get(), targetZip);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!backedUp) {
                    logger.warning("[PSGS] 备份失败 id=" + migrationId);
                    bossBar.setColor(BarColor.YELLOW);
                    bossBar.setTitle(prefix + " 备份失败，已保留原世界");
                    Bukkit.getScheduler().runTaskLater(plugin, bossBar::removeAll, 100L);
                    player.sendMessage("§e[PlayerShelter] 建筑已复制，但源世界备份失败，已保留原世界。");
                    return;
                }
                boolean deleted = psApi.deleteShelter(playerId);
                if (deleted) {
                    logger.info("[PSGS] 迁移完成 id=" + migrationId + " backup=" + targetZip.getFileName());
                    bossBar.setColor(BarColor.GREEN);
                    bossBar.setTitle(prefix + " 迁移完成");
                    Bukkit.getScheduler().runTaskLater(plugin, bossBar::removeAll, 60L);
                    player.sendMessage("§a[PlayerShelter] 你的庇护所已迁移到公会庄园（slot " + slot + "），源世界已备份。");
                } else {
                    logger.warning("[PSGS] 世界删除失败 id=" + migrationId + " world=" + worldName);
                    bossBar.setColor(BarColor.YELLOW);
                    bossBar.setTitle(prefix + " 备份完成，世界删除失败");
                    Bukkit.getScheduler().runTaskLater(plugin, bossBar::removeAll, 100L);
                    player.sendMessage("§e[PlayerShelter] 建筑已复制并备份，但旧世界删除失败：" + worldName);
                }
            });
        });
    }

    // ===== 备份工具（与 PsgsCommand 同款逻辑）=====

    private Path uniqueBackupZip(Path backupDir, String playerName, String worldName) {
        try { Files.createDirectories(backupDir); } catch (IOException e) {
            throw new IllegalStateException("无法创建备份目录: " + backupDir, e);
        }
        String base = safeFilePart(playerName) + "+" + safeFilePart(worldName);
        Path first = backupDir.resolve(base + ".zip");
        if (Files.notExists(first)) return first;
        String stamp = BACKUP_TS.format(Instant.now());
        Path stamped = backupDir.resolve(base + "-" + stamp + ".zip");
        if (Files.notExists(stamped)) return stamped;
        int i = 1;
        while (true) {
            Path c = backupDir.resolve(base + "-" + stamp + "-" + i + ".zip");
            if (Files.notExists(c)) return c;
            i++;
        }
    }

    private boolean zipDirectory(Path sourceDir, Path targetZip) {
        try {
            Files.createDirectories(targetZip.getParent());
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip));
                 Stream<Path> walk = Files.walk(sourceDir)) {
                Path root = sourceDir.toAbsolutePath().normalize();
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String entryName = root.getFileName() + "/"
                            + root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) { throw new RuntimeException(e); }
                });
            }
            return true;
        } catch (RuntimeException e) {
            logger.warning("[PSGS] 备份zip失败: " + (e.getCause() == null ? e : e.getCause()).getMessage());
            return false;
        } catch (IOException e) {
            logger.warning("[PSGS] 备份zip失败: " + e.getMessage());
            return false;
        }
    }

    private static String safeFilePart(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value;
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // ===== 工具 =====

    private GuildShelterAPI getGuildShelterApi() {
        var reg = Bukkit.getServicesManager().getRegistration(GuildShelterAPI.class);
        return reg != null ? reg.getProvider() : null;
    }

    private void runMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }
}

package org.windy.playershelter.runtime.api;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.windy.playershelter.api.BuildCheckProvider;
import org.windy.playershelter.api.PlayerShelterApi;
import org.windy.playershelter.api.PlayerShelterMigrationProgress;
import org.windy.playershelter.api.PlayerShelterMigrationRegion;
import org.windy.playershelter.api.event.ShelterDeleteEvent;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.RegionMover;
import org.windy.playershelter.runtime.PsCore;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link PlayerShelterApi} 实现（决策 #79）。只读查询 + 决策对齐，第三方附属经 ServicesManager 取本实例。
 */
public final class ApiImpl implements PlayerShelterApi {

    /** API 版本号（破坏性变更 +1）。 */
    public static final int VERSION = 6; // v5：migration region copy API；v6：异步复制 + 备份收尾 API

    private final PsCore core;

    public ApiImpl(PsCore core) {
        this.core = core;
    }

    /** 内部访问（迁移监听器等同模块内部类用）。 */
    public PsCore core() {
        return core;
    }

    @Override
    public int apiVersion() {
        return VERSION;
    }

    @Override
    public boolean hasShelter(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).isPresent();
    }

    @Override
    public String worldNameOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(Shelter::worldName).orElse(null);
    }

    @Override
    public int levelOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(Shelter::level).orElse(0);
    }

    @Override
    public boolean canBuild(String worldName, UUID actor) {
        return core.repo().findByWorldName(worldName)
                .map(s -> s.resolveRole(PlayerRef.of(actor)).canBuild())
                .orElse(false);
    }

    @Override
    public boolean canEnter(String worldName, UUID actor) {
        return core.repo().findByWorldName(worldName)
                .map(s -> s.resolveRole(PlayerRef.of(actor)).canEnter())
                .orElse(false);
    }

    @Override
    public String visibilityOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner))
                .map(s -> s.visibility().name().toLowerCase())
                .orElse(null);
    }

    @Override
    public java.util.List<String> tagsOf(UUID owner) {
        return core.tags().tagsOf(PlayerRef.of(owner));
    }

    @Override
    public Optional<PlayerShelterMigrationRegion> exportRegion(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(shelter -> regionOf(shelter, "playershelter:export"));
    }

    @Override
    public Optional<PlayerShelterMigrationRegion> prepareImport(UUID owner, int requiredSideChunks) {
        return core.repo().find(PlayerRef.of(owner)).flatMap(shelter -> {
            int side = shelter.sideChunks();
            if (side < Math.max(1, requiredSideChunks)) {
                return Optional.empty();
            }
            Shelter ready = core.world().ensureWorld(shelter);
            core.world().applyBorder(ready);
            return Optional.of(regionOf(ready, "playershelter:import"));
        });
    }

    @Override
    public boolean copyMigrationRegion(String fromWorld, int fromMinChunkX, int fromMinChunkZ, int sizeChunks,
                                       String toWorld, int toMinChunkX, int toMinChunkZ) {
        if (fromWorld == null || fromWorld.isBlank() || toWorld == null || toWorld.isBlank() || sizeChunks <= 0) {
            return false;
        }
        return core.regionMover().copyRegion(fromWorld, fromMinChunkX, fromMinChunkZ, sizeChunks,
                toWorld, toMinChunkX, toMinChunkZ);
    }

    @Override
    public void copyMigrationRegionAsync(String fromWorld, int fromMinChunkX, int fromMinChunkZ, int sizeChunks,
                                        String toWorld, int toMinChunkX, int toMinChunkZ,
                                        Consumer<PlayerShelterMigrationProgress> progress,
                                        Consumer<Boolean> whenDone) {
        if (fromWorld == null || fromWorld.isBlank() || toWorld == null || toWorld.isBlank() || sizeChunks <= 0) {
            if (whenDone != null) {
                whenDone.accept(false);
            }
            return;
        }
        RegionMover.ProgressListener listener = progress == null
                ? RegionMover.ProgressListener.NOOP
                : step -> progress.accept(new PlayerShelterMigrationProgress(
                        step.phase(), step.detail(), step.current(), step.total(), step.overall()));
        core.regionMover().copyRegionAsync(fromWorld, fromMinChunkX, fromMinChunkZ, sizeChunks,
                toWorld, toMinChunkX, toMinChunkZ, listener, whenDone);
    }

    @Override
    public Optional<Path> shelterWorldFolder(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).flatMap(shelter -> worldFolderOf(shelter.worldName()));
    }

    @Override
    public boolean deleteShelter(UUID owner) {
        Shelter shelter = core.repo().find(PlayerRef.of(owner)).orElse(null);
        if (shelter == null) {
            return false;
        }
        Bukkit.getPluginManager().callEvent(new ShelterDeleteEvent(
                shelter.owner().uuid(), shelter.worldName(), ShelterDeleteEvent.Reason.MIGRATED));
        // 世界文件不在磁盘上（早已清理）也视为删除成功，随后照常删库记录。
        boolean deleted = core.world().deleteWorld(shelter.worldName()) || worldFolderOf(shelter.worldName()).isEmpty();
        core.repo().delete(shelter.owner());
        return deleted;
    }

    @Override
    public Path dataFolder() {
        return core.plugin().getDataFolder().toPath();
    }

    @Override
    public void registerBuildCheck(org.bukkit.plugin.Plugin plugin, BuildCheckProvider provider, int priority) {
        core.buildChecks().register(plugin, provider, priority);
    }

    @Override
    public void unregisterBuildChecks(org.bukkit.plugin.Plugin plugin) {
        core.buildChecks().unregister(plugin);
    }

    /**
     * 世界目录定位（与 {@code PsgsCommand} 同款三重兜底）：
     * 已加载世界取 {@code World#getWorldFolder()}，否则按 Bukkit 容器约定依次试 {@code <container>/<name>}
     * 与 NeoForge 维度目录 {@code <container>/<mainWorld>/dimensions/minecraft/<name>}。
     */
    private Optional<Path> worldFolderOf(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            File folder = world.getWorldFolder();
            if (folder != null && folder.isDirectory()) {
                return Optional.of(folder.toPath());
            }
        }
        File container = Bukkit.getWorldContainer();
        File direct = new File(container, worldName);
        if (direct.isDirectory()) {
            return Optional.of(direct.toPath());
        }
        String mainWorld = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        File dimension = new File(container, mainWorld + File.separator + "dimensions"
                + File.separator + "minecraft" + File.separator + worldName);
        if (dimension.isDirectory()) {
            return Optional.of(dimension.toPath());
        }
        return Optional.empty();
    }

    private PlayerShelterMigrationRegion regionOf(Shelter shelter, String source) {
        int side = shelter.sideChunks();
        int minChunkX = Math.floorDiv(shelter.layout().originX(), 16);
        int minChunkZ = Math.floorDiv(shelter.layout().originZ(), 16);
        return new PlayerShelterMigrationRegion(
                shelter.owner().uuid(),
                shelter.worldName(),
                minChunkX,
                minChunkZ,
                minChunkX + side - 1,
                minChunkZ + side - 1,
                side,
                shelter.level(),
                source);
    }
}

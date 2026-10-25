package org.windy.playershelter.adapter.world;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.playershelter.api.terrain.TerrainProvider;
import org.windy.playershelter.api.terrain.TerrainRequest;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.WorldControl;
import org.windy.playershelter.runtime.api.TerrainProviderRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * {@link WorldControl} 的 Paper 实现（走 org.bukkit API，普通版载体）。
 *
 * <p>生成类型分流：
 * <ul>
 * <li>{@link GenerationType#NATURAL}：有地形 provider 认领时交给它（如独立的 Iris 扩展插件），
 * 无人认领或 provider 失败则回退原版 normal。主插件本体<b>不认识任何地形引擎</b>。</li>
 * <li>{@link GenerationType#FLAT}：超平坦，generatorSettings 走 JSON（MC1.16+ 要求，见 [[mc-flat-generatorsettings-json]]）。</li>
 * <li>{@link GenerationType#VOID}：原版 FLAT + 空层超平坦（{@code VOID_SETTINGS_JSON}）+ 出生点摆起始小岛（决策 #45）。</li>
 * </ul>
 *
 * <p><b>混合端友好</b>：FLAT/VOID 均用<b>原版超平坦生成器</b>（非 Bukkit 自定义 {@code ChunkGenerator}），
 * 故在 Youer 混合端也能正常生成——绕开了「混合端不 honor 自定义 ChunkGenerator」的坑，无需 NeoForge 原生维度。
 */
public final class PaperWorldControl implements WorldControl {

    private static final String FLAT_SETTINGS_JSON =
            "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1}," +
                    "{\"block\":\"minecraft:deepslate\",\"height\":59}," +
                    "{\"block\":\"minecraft:stone\",\"height\":64}," +
                    "{\"block\":\"minecraft:dirt\",\"height\":3}," +
                    "{\"block\":\"minecraft:grass_block\",\"height\":1}]," +
                    "\"biome\":\"minecraft:plains\"}";

    /**
     * 虚空 = 空层超平坦（决策 #1 VOID）。<b>关键</b>：用<b>原版 FLAT 生成器</b>（空 layers = 纯虚空），
     * 而非 Bukkit 自定义 {@code ChunkGenerator}——这样在 Youer 混合端也能正常生成（混合端不 honor
     * 自定义 ChunkGenerator，但认原版超平坦），免去 NeoForge 原生维度的深水活。起始小岛事后摆。
     */
    private static final String VOID_SETTINGS_JSON =
            "{\"layers\":[],\"biome\":\"minecraft:plains\"}";

    private final Plugin plugin;
    private final Logger log;
    private final Material platformMaterial;
    private final SafeLanding safeLanding;
    private final TerrainProviderRegistry terrainProviders;
    private final Map<String, String> gamerules;
    private final int viewDistance;
    private final int simulationDistance;

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             TerrainProviderRegistry terrainProviders, boolean keepInventory) {
        this(plugin, log, platformMaterial, terrainProviders,
                keepInventory ? Map.of("keep_inventory", "true") : Map.of(), 6, 4);
    }

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             TerrainProviderRegistry terrainProviders, Map<String, String> gamerules) {
        this(plugin, log, platformMaterial, terrainProviders, gamerules, 6, 4);
    }

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             TerrainProviderRegistry terrainProviders, Map<String, String> gamerules,
                             int viewDistance, int simulationDistance) {
        this.plugin = plugin;
        this.log = log;
        this.platformMaterial = platformMaterial == null ? Material.GLASS : platformMaterial;
        this.safeLanding = new SafeLanding(this.platformMaterial);
        this.terrainProviders = terrainProviders;
        this.gamerules = gamerules == null ? Map.of() : Map.copyOf(gamerules);
        this.viewDistance = clampPaperDistance(viewDistance, 6);
        this.simulationDistance = clampPaperDistance(simulationDistance, 4);
    }

    @Override
    public String worldName(Shelter shelter) {
        return shelter.worldName();
    }

    @SuppressWarnings("unchecked") // 压制 Registry 获取时的泛型强转警告
    @Override
    public Shelter ensureWorld(Shelter shelter) {
        String name = shelter.worldName();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            applyPaperWorldTuning(existing);
            applyBorder(shelter);
            return shelter;
        }

        boolean freshWorld = !worldFolderExists(name);
        WorldCreator wc = new WorldCreator(name).seed(shelter.seed());
        switch (shelter.genType()) {
            case NATURAL -> wc.environment(World.Environment.NORMAL).type(WorldType.NORMAL);
            case FLAT -> {
                wc.environment(World.Environment.NORMAL).type(WorldType.FLAT);
                wc.generatorSettings(FLAT_SETTINGS_JSON);
            }
            case VOID -> {
                wc.environment(World.Environment.NORMAL).type(WorldType.FLAT);
                wc.generatorSettings(VOID_SETTINGS_JSON);
            }
        }
        World world = wc.createWorld();
        if (world == null) {
            throw new IllegalStateException("createWorld 返回 null: " + name);
        }

        // 1.21 注册表：取消常加载
        GameRule<Integer> spawnRadius = (GameRule<Integer>) lookupGameRule("spawn_chunk_radius");
        if (spawnRadius != null) {
            world.setGameRule(spawnRadius, 0);
        }
        applyPaperWorldTuning(world);

        world.setSpawnFlags(true, true);

        if (freshWorld) {
            applyConfiguredGameRules(world);
        }

        if (shelter.genType() == GenerationType.VOID) {
            buildStarterIsland(world);
        }

        // 出生点落在锚定角第一个 chunk 中心；地块范围固定为 chunk (0..max-1, 0..max-1)。
        Location spawn = safeLanding.resolve(world, 8, 8);
        world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
        applyBorder(shelter);
        log.info("[PlayerShelter] 已创建庇护所世界 " + name + " (" + shelter.genType() + ")");

        if (shelter.genType() == GenerationType.NATURAL) {
            probeNaturalTerrain(world, name);
        }
        return shelter;
    }

    /** 采样自然地形是否真生成（混合端诊断）。仅日志，不改变行为。 */
    private void probeNaturalTerrain(World world, String name) {
        int hCenter = world.getHighestBlockYAt(0, 0);
        int hFar = world.getHighestBlockYAt(40, 40);
        int minY = world.getMinHeight();
        boolean centerVoid = hCenter <= minY + 1;
        boolean farVoid = hFar <= minY + 1;
        log.info("[PlayerShelter] 地形自检 " + name + "：地表高度 (0,0)=" + hCenter + " (40,40)=" + hFar
                + " 世界底=" + minY);
        if (centerVoid || farVoid) {
            log.warning("[PlayerShelter] ⚠ 该 NATURAL 世界地形疑似未生成（多处为虚空高度）。"
                    + "混合端(Youer/NeoForge)常不为 Bukkit 建的普通世界挂模组地形生成器 → 会是空世界。");
            log.warning("[PlayerShelter]   解决：① 安装地形生成扩展（如 Iris 扩展插件，注册 TerrainProvider 后自动接管）；"
                    + "② 或用 /ps create flat|void（原版超平坦，混合端可靠生成）。");
        }
    }

    /**
     * 覆盖建世界（决策 #26 进度条）：NATURAL 且有地形 provider 认领时，交由 provider 生成
     * （provider 可自行异步，如 Iris）；否则调用 {@code super} 走默认实现——
     * <b>即 FLAT/VOID/无人认领/已加载/plugin 缺失/provider 故障等所有回退路径都在调用线程同步建完</b>，
     * 因为原版 {@code WorldCreator} 必须主线程（见 {@link WorldControl#ensureWorldAsync}）。
     *
     * <p>provider 失败（认领后抛异常或 future 异常完成）时<b>回退原版 vanilla</b>，只记警告——
     * 扩展出问题不该让玩家建不了家园。
     */
    @SuppressWarnings("unchecked") // 压制 Registry 获取时的泛型强转警告
    @Override
    public void ensureWorldAsync(Shelter shelter, UUID progressAudience,
                                 Consumer<Shelter> onReady, Runnable onError) {
        String name = shelter.worldName();
        boolean delegable = shelter.genType() == GenerationType.NATURAL
                && terrainProviders != null
                && Bukkit.getWorld(name) == null;
        if (!delegable) {
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }

        boolean freshBuild = !worldFolderExists(name);
        TerrainRequest request = new TerrainRequest(name, shelter.seed(), freshBuild, progressAudience,
                "paper", shelter.genType().name().toLowerCase(java.util.Locale.ROOT));
        TerrainProvider provider;
        try {
            provider = terrainProviders.select(request);
        } catch (Throwable t) {
            provider = null;
        }
        if (provider == null) {
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }
        if (plugin == null) {
            log.severe("[PlayerShelter] 地形 provider 需异步调度但未注入 plugin，回退原版生成。");
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }

        log.info("[PlayerShelter] " + (freshBuild ? "创建" : "重载") + " 世界(由 provider '" + provider.id() + "' 生成): "
                + name);

        final TerrainProvider owner = provider;
        CompletableFuture<World> future;
        try {
            future = owner.generateAsync(request);
        } catch (Throwable t) {
            log.warning("[PlayerShelter] 地形 provider '" + owner.id() + "' 生成失败，回退原版: " + t.getMessage());
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }
        if (future == null) {
            log.warning("[PlayerShelter] 地形 provider '" + owner.id() + "' 返回 null future，回退原版。");
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }

        future.whenComplete((w, error) -> runOnMain(() -> {
            if (error != null || w == null) {
                log.warning("[PlayerShelter] 地形 provider '" + owner.id() + "' 生成失败，回退原版: "
                        + (error == null ? "返回 null 世界" : String.valueOf(error.getMessage())));
                WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
                return;
            }
            try {
                finishGeneratedWorld(shelter, w, freshBuild);
                onReady.accept(shelter);
            } catch (Throwable t) {
                log.severe("[PlayerShelter] 世界后处理失败: " + t.getMessage());
                onError.run();
            }
        }));
    }

    /**
     * provider 交出已加载世界后的统一后处理：取消常加载、应用世界参数、出生点、gamerule、边界。
     * 与同步 {@link #ensureWorld} 的收尾保持一致，避免两条路径行为漂移。
     */
    @SuppressWarnings("unchecked")
    private void finishGeneratedWorld(Shelter shelter, World world, boolean freshBuild) {
        GameRule<Integer> spawnRadius = (GameRule<Integer>) lookupGameRule("spawn_chunk_radius");
        if (spawnRadius != null) {
            world.setGameRule(spawnRadius, 0);
        }
        applyPaperWorldTuning(world);
        world.setSpawnFlags(true, true);

        if (freshBuild) {
            Location spawn = safeLanding.resolve(world, 8, 8);
            world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            applyConfiguredGameRules(world);
        }
        applyBorder(shelter);
        log.info("[PlayerShelter] 庇护所世界就绪 " + world.getName() + " (" + shelter.genType() + ")");
    }

    /** provider 的回调线程不确定，统一切回主线程。 */
    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (plugin == null) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException e) {
            // 插件正在卸载，调度器已关：直接跑，失败也无所谓
            task.run();
        }
    }

    // 兼容层：26.2 起 gamerule 走注册表 Registry.GAME_RULE；1.21.1 及更早只有 GameRule.getByName（camelCase）。
    // 反射访问，保证同一份代码在新旧 Paper 上都能编译、运行。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static GameRule<?> lookupGameRule(String snakeKey) {
        try {
            Object reg = Registry.class.getField("GAME_RULE").get(null);
            Object rule = ((Registry) reg).get(NamespacedKey.minecraft(snakeKey));
            if (rule instanceof GameRule) {
                return (GameRule<?>) rule;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // 旧版 Paper：走 GameRule.getByName 兜底
        }
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : snakeKey.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            camel.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return GameRule.getByName(camel.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyConfiguredGameRules(World world) {
        for (Map.Entry<String, String> e : gamerules.entrySet()) {
            String key = e.getKey().toLowerCase();
            GameRule rule = (GameRule) lookupGameRule(key);
            if (rule == null) {
                log.warning("[PlayerShelter] 未知 gamerule: " + key);
                continue;
            }
            Class<?> type = rule.getType();
            try {
                if (type == Boolean.class) {
                    world.setGameRule(rule, Boolean.parseBoolean(e.getValue()));
                } else if (type == Integer.class) {
                    world.setGameRule(rule, Integer.parseInt(e.getValue()));
                } else {
                    log.warning("[PlayerShelter] 不支持的 gamerule 类型: " + key + " -> " + type.getName());
                }
            } catch (Exception ex) {
                log.warning("[PlayerShelter] gamerule 值无效: " + key + "=" + e.getValue());
            }
        }
    }

    /** 虚空起始小岛（决策 #45）：锚定角出生点附近一小块草地。 */
    private void buildStarterIsland(World world) {
        int cy = 64;
        for (int dx = 6; dx <= 10; dx++) {
            for (int dz = 6; dz <= 10; dz++) {
                world.getBlockAt(dx, cy, dz).setType(Material.GRASS_BLOCK);
                world.getBlockAt(dx, cy - 1, dz).setType(Material.DIRT);
                world.getBlockAt(dx, cy - 2, dz).setType(Material.DIRT);
            }
        }
        world.getBlockAt(10, cy + 1, 10).setType(Material.OAK_SAPLING);
        world.getBlockAt(6, cy + 1, 6).setType(Material.CHEST);
    }

    private int clampPaperDistance(int value, int fallback) {
        int v = value <= 0 ? fallback : value;
        return Math.max(2, Math.min(32, v));
    }

    private void applyPaperWorldTuning(World world) {
        try {
            world.setViewDistance(viewDistance);
            world.setSimulationDistance(simulationDistance);
        } catch (RuntimeException e) {
            log.warning("[PlayerShelter] Failed to apply Paper world distances for "
                    + world.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void applyBorder(Shelter shelter) {
        World world = Bukkit.getWorld(shelter.worldName());
        if (world == null) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        // WorldBorder 就是当前可用正方形，从角落锚点向 +X/+Z 扩张。
        double size = shelter.borderSize();
        border.setCenter(shelter.layout().borderCenterXAtLevel(shelter.level()),
                shelter.layout().borderCenterZAtLevel(shelter.level()));
        border.setSize(shelter.borderSize());
        border.setWarningDistance(4);
        border.setDamageBuffer(2);
    }

    @Override
    public boolean unload(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            log.info("[PlayerShelter] unload(" + worldName + "): 世界不存在，跳过");
            return false;
        }
        if (!world.getPlayers().isEmpty()) {
            log.info("[PlayerShelter] unload(" + worldName + "): 内有 " + world.getPlayers().size() + " 名玩家，跳过");
            return false; // 决策 #27 有人不卸载
        }
        int chunks = world.getLoadedChunks().length;
        log.info("[PlayerShelter] unload(" + worldName + "): 开始卸载，loadedChunks~" + chunks);
        boolean ok = Bukkit.unloadWorld(world, true); // save=true
        log.info("[PlayerShelter] unload(" + worldName + "): " + (ok ? "成功" : "失败"));
        return ok;
    }

    @Override
    public boolean deleteWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        File loadedFolder = null;
        if (world != null) {
            loadedFolder = world.getWorldFolder();
            // 先把里面的人踢到主世界（决策 #65 回大厅），再卸载。
            World fallback = Bukkit.getWorlds().get(0);
            world.getPlayers().forEach(p -> p.teleport(fallback.getSpawnLocation()));
            Bukkit.unloadWorld(world, false); // 不保存，反正要删
        }
        // 逐个删除所有候选位置（混合端存 world/dimensions/minecraft/ 下，与经典根目录不同）。
        boolean any = false;
        for (File f : candidateFolders(worldName, loadedFolder)) {
            if (f != null && f.isDirectory()) {
                log.info("[PlayerShelter] 删除世界目录: " + f.getAbsolutePath());
                any = deleteRecursively(f.toPath()) || any;
            }
        }
        return any;
    }

    /**
     * 世界存档文件夹的候选位置。<b>混合端(Youer/NeoForge)把 Bukkit 建的世界存为维度</b>，
     * 目录在 {@code <主世界>/dimensions/minecraft/<name>}，而非经典 CraftBukkit 的根目录 {@code <container>/<name>}。
     * 都列出来，谁存在删谁。加载中的世界用 {@code getWorldFolder()} 权威路径。
     */
    private List<File> candidateFolders(String worldName, File loadedFolder) {
        List<File> out = new ArrayList<>();
        if (loadedFolder != null) {
            out.add(loadedFolder);
        }
        File container = Bukkit.getWorldContainer();
        out.add(new File(container, worldName)); // 经典 CraftBukkit 根目录平铺
        String mainWorld = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        out.add(new File(container, mainWorld + File.separator + "dimensions"
                + File.separator + "minecraft" + File.separator + worldName)); // 混合端维度目录
        return out;
    }

    private boolean deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.log(Level.WARNING, "[PlayerShelter] 删除文件失败: " + p, e);
                }
            });
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除世界目录失败: " + path, e);
            return false;
        }
    }

    @Override
    public boolean isLoaded(String worldName) {
        return Bukkit.getWorld(worldName) != null;
    }

    @Override
    public int playerCount(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? 0 : world.getPlayers().size();
    }

    @Override
    public List<String> loadedShelterWorlds() {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().startsWith("shelter_")) {
                out.add(w.getName());
            }
        }
        return out;
    }

    /** 该世界是否惰性生成（由注册的地形 provider 声明；无 provider 则为 false）。 */
    @Override
    public boolean lazilyGenerated(String worldName) {
        return terrainProviders != null && terrainProviders.lazilyGenerated(worldName);
    }

    /** 世界存档目录是否已存在（判首建 vs 加载）。查所有候选位置（含混合端 dimensions 目录）。 */
    private boolean worldFolderExists(String worldName) {
        for (File f : candidateFolders(worldName, null)) {
            if (f != null && f.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /** 惰性世界（玩家走到哪生成到哪）用的安全落点：出生点处铺站脚台，传送时触发单区块生成。 */
    public Location safeSpawn(World world) {
        Location s = world.getSpawnLocation();
        return safeLanding.resolve(world, s.getBlockX(), s.getBlockZ());
    }
}

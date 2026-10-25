package org.windy.playershelter.irisbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.playershelter.api.PlayerShelterApi;

/**
 * PlayerShelter 的 Iris 地形生成扩展入口。
 *
 * <p>本插件<b>不依赖</b>主插件内部实现，只经 {@link PlayerShelterApi}（ServicesManager 取的公开接口）
 * 注册一个 {@link IrisTerrainProvider}，接管 NATURAL 世界的生成。因此可以独立构建、独立发布：
 * <ul>
 *   <li>PlayerShelter 本体构建时<b>完全不需要</b> Iris jar；</li>
 *   <li>Iris 缺席时本插件不会加载（paper-plugin.yml 里 required: true）；</li>
 *   <li>Iris API 升级只需重编本 jar，主插件零改动。</li>
 * </ul>
 */
public final class PlayerShelterIrisBridgePlugin extends JavaPlugin {

    /** 本扩展要求的最低 PlayerShelter API 版本（地形 provider SPI 自 v7 起）。 */
    private static final int REQUIRED_API_VERSION = 7;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("[IrisBridge] 配置 enabled=false，不接管地形生成（走主插件原版行为）。");
            return;
        }
        PlayerShelterApi psApi = getServer().getServicesManager().load(PlayerShelterApi.class);
        if (psApi == null) {
            getLogger().warning("[IrisBridge] 未检测到 PlayerShelter API，扩展停用。请确认 PlayerShelter 已正常启动。");
            return;
        }
        if (psApi.apiVersion() < REQUIRED_API_VERSION) {
            getLogger().warning("[IrisBridge] PlayerShelter API 版本过旧（当前 " + psApi.apiVersion()
                    + "，需要 " + REQUIRED_API_VERSION + "），扩展停用。请升级 PlayerShelter。");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Iris") == null) {
            getLogger().warning("[IrisBridge] 未检测到 Iris 插件，扩展停用。");
            return;
        }
        String dimension = getConfig().getString("dimension", "overworld");
        psApi.registerTerrainProvider(this, new IrisTerrainProvider(this, dimension));
        getLogger().info("[IrisBridge] 已接管 NATURAL 世界生成（Iris 维度 '" + dimension + "'）。");
    }
}

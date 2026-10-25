package org.windy.playershelter.gsbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.playershelter.api.PlayerShelterApi;

/**
 * PlayerShelter ↔ GuildShelter 互操作扩展入口。
 *
 * <p>本插件<b>不依赖</b>主插件内部实现，只经 {@link PlayerShelterApi}（ServicesManager 取的公开接口）与
 * GuildShelter 交互，因此可以独立构建、独立发布、独立升级：
 * <ul>
 *   <li>PlayerShelter 本体构建时<b>完全不需要</b> GuildShelter API jar；</li>
 *   <li>GuildShelter 缺席时本插件静默待机（不注册监听器、不影响任何东西）；</li>
 *   <li>GuildShelter API 升级只需重编本 jar，主插件零改动。</li>
 * </ul>
 */
public final class PlayerShelterGsBridgePlugin extends JavaPlugin {

    /** 本扩展要求的最低 PlayerShelter API 版本（异步复制 + 备份收尾接口自 v6 起）。 */
    private static final int REQUIRED_API_VERSION = 6;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("[GSBridge] 配置 enabled=false，自动迁移已关闭。");
            return;
        }
        PlayerShelterApi psApi = getServer().getServicesManager().load(PlayerShelterApi.class);
        if (psApi == null) {
            getLogger().warning("[GSBridge] 未检测到 PlayerShelter API，扩展停用。请确认 PlayerShelter 已正常启动。");
            return;
        }
        if (psApi.apiVersion() < REQUIRED_API_VERSION) {
            getLogger().warning("[GSBridge] PlayerShelter API 版本过旧（当前 " + psApi.apiVersion()
                    + "，需要 " + REQUIRED_API_VERSION + "），扩展停用。请升级 PlayerShelter。");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("GuildShelter") == null) {
            getLogger().info("[GSBridge] 未安装 GuildShelter，自动迁移待机中（安装后可随启动生效）。");
            return;
        }
        String prefix = getConfig().getString("prefix", "[PSGS]");
        getServer().getPluginManager().registerEvents(new ManorMigrationListener(this, psApi, prefix), this);
        getLogger().info("[GSBridge] GuildShelter 软集成已启用（获得庄园时自动迁移庇护所）。");
    }
}

# PlayerShelter

一人一世界的个人庇护所插件。每个玩家拥有一个独立 MC 世界，等级越高，边界越大。

## 模块

- `playershelter-api`: 第三方附属 API
- `core`: 核心逻辑与测试
- `paper-common`: Paper/Bukkit 适配层
- `paper`: 普通 Paper 插件 jar
- `gs-bridge`: GuildShelter 互操作扩展（独立 jar，Optional；需本地 `libs/guildshelter-api-*.jar` 才会纳入构建）
- `iris-bridge`: Iris 地形生成扩展（独立 jar，Optional；需本地 `libs/iris-api-*.jar` 才会纳入构建）
- `neoforge_26_2`: NeoForge 26.2 增强 jar

## 构建

```powershell
.\gradlew.bat :core:test :paper-common:test
.\gradlew.bat :paper:build
.\gradlew.bat :neoforge_26_2:build
```

产物：

- `paper/build/libs/playershelter-paper-*.jar`
- `gs-bridge/build/libs/playershelter-gs-bridge-*.jar`
- `iris-bridge/build/libs/playershelter-iris-bridge-*.jar`
- `neoforge_26_2/build/libs/playershelter-neoforge-26.2-*.jar`

## GuildShelter 互操作（可选）

`gs-bridge` 是独立扩展 jar：装上它且服务器同时有 GuildShelter 时，玩家获得公会庄园会自动把庇护所建筑迁过去。
主插件本体**不依赖** GuildShelter API，`libs/` 里没有 `guildshelter-api-*.jar` 时整个模块不参与构建。

## 自定义地形生成（可选）

主插件默认用原版生成。装扩展插件后可接管地形：

- 扩展在 onEnable 里调 `PlayerShelterApi#registerTerrainProvider(...)` 注册一个 `TerrainProvider`
- `NATURAL` 世界的生成即交给它（可返回惰性地形）；无人认领或 provider 出错时静默回退原版
- 主插件本体不认识 Iris 等任何地形引擎

`iris-bridge` 就是这样一个现成扩展：装上它 + Iris 后，`NATURAL` 世界改由 Iris 维度生成
（含惰性生成与生成进度条）。配置项 `enabled` / `dimension` 在它自己的 `config.yml` 里。

## 功能

- 每个玩家拥有独立世界，支持 SQLite / MySQL 存储
- 命令：`/ps create|home|leave|setspawn|info|gui|upgrade|reset|visibility|trust|visit|list|like|flag|board|msg` 等
- GUI：`/ps gui` 打开庇护所控制器
- 等级系统：通过 `levels.yml` 管理边界大小、升级费用、成员名额和实体限额
- 保护：访客限制、越界保护、PvP/刷怪/爆炸/火焰/容器/交互保护
- 跨服：支持 BungeeCord / Velocity 跨服交接

## PlaceholderAPI

玩家：`%playershelter_has%`、`_level%`、`_maxlevel%`、`_border%`、`_visibility%`、`_likes%`、`_world%`、`_tags%`、`_messages%`、`_admins%`、`_trusted%`、`_admincap%`、`_trustcap%`、`_upgradecost%`

全局：`%playershelter_loaded_worlds%`、`%playershelter_total_shelters%`

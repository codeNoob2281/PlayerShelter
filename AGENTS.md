# AGENTS.md

本文用于指导 AI 助手在本项目中的工作。

## 一句话现状

**主插件本体完全不依赖 GuildShelter（下称 GS）**。所有 GS 相关代码只出现在两处：一个独立扩展模块
`gs-bridge`（编译期依赖 GS API），和主插件里一个纯反射、零编译依赖的 `/ps migrate` 命令实现。
`libs/guildshelter-api-*.jar` 被 `.gitignore` 排除，**不会进 GitHub**。

## 模块与 GS 的关系

| 模块 | 是否依赖 GS | 说明 |
|---|---|---|
| `playershelter-api` | 否 | 对外扩展 API（纯接口 + DTO + 事件）。`apiVersion` 当前 = **6** |
| `core` | 否 | 平台无关 domain/service/persistence |
| `paper-common` | **否** | GS jar 由 `build.gradle` 的 `fileTree('libs')` 显式 `exclude 'guildshelter-*'` |
| `paper` | 否 | 薄入口 + shade |
| `gs-bridge` | **是（仅 compileOnly）** | 独立扩展 jar；`libs/` 里没有 GS jar 时连 Gradle 模块都不会 include |
| `neoforge_26_2` / `neoforge_1.21.1` | — | 本地私有（`.gitignore`），本机通常不存在 |

验证方式（改构建后建议跑一次）：把 `libs/guildshelter-api-*.jar` 临时移走，`gradlew clean build`
应当照样成功，且 `gradlew projects` 里看不到 `:gs-bridge`。

## GS 相关代码分布

**1. `gs-bridge/`（扩展 jar，推荐改动入口）**
- `ManorMigrationListener` — 监听 GS `ManorAssignedEvent`，玩家获得庄园时自动迁移庇护所建筑
- 只经 `PlayerShelterApi` 公开接口与主插件交互，**不碰 `PsCore` 等内部实现**
- 依赖声明：`PlayerShelter: required`、`GuildShelter: optional`，均 `load: AFTER, join-classpath: true`
- 自己的 `config.yml`（`enabled` / `prefix`）

**2. `paper-common/.../command/PsgsCommand.java`（911 行，留在主插件）**
- 手动互迁命令，`/ps migrate` 的子命令转发到它
- 对 GS **零编译依赖**：`Class.forName("org.windy.guildshelter.api.GuildShelterAPI")` + `Method.invoke` 全反射
- 依赖 `paper-plugin.yml` 里的 `GuildShelter: { load: AFTER, required: false, join-classpath: true }`
  才能让反射看见 GS 类——这是**运行期**软依赖，不是编译期
- ⚠️ 暂不迁移、暂不重构（见下「已知遗留问题」）

## 已知遗留问题（有意不动）

- `PsgsBrigadier.java` 是**死代码**：写好了但全项目零引用，`/psgs` 命令实际没注册。对外入口只有 `/ps migrate`。

## 构建

需要 JDK 21 或更高（低版本 Gradle 无法运行）。

```powershell
.\gradlew.bat clean build
```

产物：`paper/build/libs/playershelter-paper-*.jar`、`gs-bridge/build/libs/playershelter-gs-bridge-*.jar`

## 硬性规矩

- **不要擅自 `git commit` / `git push`**。改完只汇报，等明确指令。
- 主插件代码禁止 `import org.windy.guildshelter.*`。需要 GS 能力时：优先加 `PlayerShelterApi` 接口，
  或写进 `gs-bridge`——不要往 `paper-common` 里塞 GS 引用。
- 给 `PlayerShelterApi` 加/改方法时同步提升 `ApiImpl.VERSION`，且 `gs-bridge` 里用
  `REQUIRED_API_VERSION` 守住下限。

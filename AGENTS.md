# AGENTS.md

本文用于指导 AI 助手在本项目中的工作。

## 一句话现状

**主插件本体既不依赖 GuildShelter（下称 GS），也不依赖任何地形引擎（Iris 等）。**

- GS 相关代码只在两处：独立扩展模块 `gs-bridge`（编译期依赖 GS API），和主插件里一个纯反射、
  零编译依赖的 `/ps migrate` 命令实现。`libs/guildshelter-api-*.jar` 被 `.gitignore` 排除，**不进 GitHub**。
- 地形生成走 SPI：主插件默认原版 vanilla，扩展插件注册 `TerrainProvider` 即可接管（`iris-bridge` 即其一）。
  主插件源码里**没有任何** `com.volmit.iris.*` 引用。

## 模块与扩展依赖的关系

| 模块 | 依赖外部 API | 说明 |
|---|---|---|
| `playershelter-api` | 否 | 对外扩展 API（纯接口 + DTO + 事件）。`apiVersion` 当前 = **7** |
| `core` | 否 | 平台无关 domain/service/persistence |
| `paper-common` | **否** | GS jar 由 `build.gradle` 的 `fileTree('libs')` 显式 `exclude 'guildshelter-*'` |
| `paper` | 否 | 薄入口 + shade |
| `gs-bridge` | **GuildShelter（仅 compileOnly）** | 独立扩展 jar；`libs/` 里没有 GS jar 时连 Gradle 模块都不会 include |
| `iris-bridge` | **Iris（仅 compileOnly）** | 独立扩展 jar；`libs/` 里没有 `iris-*.jar` 时整个模块不 include |
| `neoforge_26_2` / `neoforge_1.21.1` | — | 本地私有（`.gitignore`），本机通常不存在 |

验证方式（改构建后建议跑一次）：把 `libs/guildshelter-api-*.jar` 或 `libs/iris-api-*.jar` 临时移走，
`gradlew clean build` 应当照样成功，且 `gradlew projects` 里看不到对应 `:gs-bridge` / `:iris-bridge`。

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

## 地形生成扩展（SPI）
主插件不硬编码地形引擎。`PaperWorldControl.ensureWorldAsync` 建 NATURAL 世界时：

1. 按 `priority()` 从低到高问已注册的 `TerrainProvider`（`playershelter-api` 的 `api.terrain` 包）
2. 第一个 `supports()` 返回 true 的负责生成，返回 `CompletableFuture<World>`
3. **全部不认领、或认领者抛异常/返回 null → 静默回退原版 vanilla normal**，只记警告

- 注册入口：`PlayerShelterApi#registerTerrainProvider(Plugin, TerrainProvider)`（apiVersion 7 起）
- `lazilyGenerated(worldName)` 为 true 时，扩界后不预生成新环（惰性地形）
- 归属插件被禁用 → 其 provider 自动跳过，不要求显式注销
- 同步路径 `ensureWorld` **完全不受影响**，仍是原版 `WorldCreator`
- 曾经的 `IrisSupport.java` 已删除；Iris 支持改由 `iris-bridge` 模块实现 `TerrainProvider` 提供。

## Iris 扩展（`iris-bridge/`）

- `IrisTerrainProvider` — 只认领 `genType == "natural"`，其余交给主插件原版
- 调 Iris 的 `IrisToolbelt.createWorld()`（**必须在异步线程**，其内部自行 submit 回主线程）
- `lazilyGenerated` 用 `IrisToolbelt.isIrisWorld(world)` 判定
- 依赖 `PlayerShelter: required` + `Iris: required`，均 `load: AFTER, join-classpath: true`
- 自己的 `config.yml`（`enabled` / `dimension`）

⚠️ **目标 Iris 版本是 3.9.x，包名 `com.volmit.iris.*`，支持 MC 1.20.1–1.21.11 / Java 21。**

**不要升到 Iris 4.x**：4.x 改名为 `art.arcane.iris.*`，且只支持 MC 26.x、要求 Java 25 字节码，
与本项目（1.21.11 + JDK 21）完全不兼容。

`libs/iris-api-3.9.2.jar` 就是完整可运行的 Iris 插件 jar（3.1MB，含 `v1_21_R7` NMS 绑定），
直接丢进服务端 `plugins/` 就能用，同时充当 `iris-bridge` 的编译期依赖。

构建它的流程（源码 worktree 在 `E:\Work\MinecraftWork\Iris-3.9.2`，tag `3.9.2-1.20.1-1.21.11`）：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
gradle -p E:\Work\MinecraftWork\Iris-3.9.2 jar --no-daemon    # 产物 build/libs/Iris-3.9.2-*.jar
```

本地已做的三处必要适配（都在 3.9.2 worktree 里，不属于本项目）：
1. `build.gradle.kts` 加 `iris-local-repo` 仓库（file://），因为 ItemsAdder 官方仓库已下线、
   fastutil / Mythic / Spigot NMS 大文件下载不稳定
2. 只打包 `v1_21_R7` 这一个 NMS 绑定（其余版本要 Spigot NMS 仓库的 remapped 包，测试用不到）
3. `core/generation-kernels/catalog.tsv` 必须保持 **LF** 换行（其校验代码硬性拒绝 `\r`，
   Windows 的 `autocrlf=true` 会把它转成 CRLF 导致 `Invalid generation kernel catalog`）

## 构建

需要 **JDK 21**（`build.gradle` 的 `java.toolchain.languageVersion = 21`）。

⚠️ 依赖版本受 JDK 21 约束，不要随意升：
- `worldedit-*` 锁 **7.4.2** —— 7.4.3+ 是 Java 25 字节码，会让 JDK 21 构建失败
- `libs/iris-api-*.jar` 必须是 3.9.x（Java 21 字节码）

```powershell
.\gradlew.bat clean build
```

产物：`paper/build/libs/playershelter-paper-*.jar`、`gs-bridge/build/libs/playershelter-gs-bridge-*.jar`、
`iris-bridge/build/libs/playershelter-iris-bridge-*.jar`

## 硬性规矩

- **不要擅自 `git commit` / `git push`**。改完只汇报，等明确指令。
- 主插件代码禁止 `import org.windy.guildshelter.*`。需要 GS 能力时：优先加 `PlayerShelterApi` 接口，
  或写进 `gs-bridge`——不要往 `paper-common` 里塞 GS 引用。
- 给 `PlayerShelterApi` 加/改方法时同步提升 `ApiImpl.VERSION`，且 `gs-bridge` 里用
  `REQUIRED_API_VERSION` 守住下限。

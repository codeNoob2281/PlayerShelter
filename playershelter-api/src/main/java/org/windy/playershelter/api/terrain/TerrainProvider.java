package org.windy.playershelter.api.terrain;

import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * 地形生成扩展点（apiVersion 7）。
 *
 * <p>主插件不再硬编码任何地形引擎：它建 NATURAL 世界时先按 {@link #priority()} 从低到高询问已注册的
 * provider，谁 {@link #supports} 就由谁生成；无人认领则走原版 vanilla normal。
 * 这样 Iris 之类的引擎可以做成独立扩展插件，主插件本体零依赖。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>{@link #generateAsync} 可能在<b>任意线程</b>被调用（默认走异步线程），实现方自行切回主线程做
 *       `addWorld` 之类必须主线程的动作。</li>
 *   <li>返回的 future 应给出<b>已加载</b>的 {@link World}；主插件随后负责出生点、边界、gamerule。</li>
 *   <li>抛出异常或 future 异常完成时，主插件记一条警告并<b>回退原版 vanilla</b> 生成——
 *       扩展出问题不该让玩家建不了家园。</li>
 *   <li>实现方需自行判重：同一世界名重复调用应当返回既有世界而非重复创建。</li>
 * </ul>
 *
 * <p>注册见 {@code PlayerShelterApi#registerTerrainProvider}。
 */
public interface TerrainProvider {

    /** 稳定标识，用于日志与排障（如 {@code iris}）。 */
    String id();

    /**
     * 优先级，<b>数值小的先被询问</b>。想覆盖默认行为给负数，作为兜底给较大正数。
     */
    default int priority() {
        return 0;
    }

    /**
     * 是否认领这次请求。返回 {@code false} 或抛异常都视为不适用（主插件继续问下一个，最终回退 vanilla）。
     */
    boolean supports(TerrainRequest request);

    /**
     * 执行生成，返回已加载的世界。
     *
     * @return future 完成后提供已加载的 {@link World}
     */
    CompletableFuture<World> generateAsync(TerrainRequest request);

    /**
     * 该世界的地形是否惰性生成（玩家走到哪生成到哪）。
     *
     * <p>为 {@code true} 时主插件扩界后<b>不</b>立刻预生成新环，避免抵消惰性、卡服。
     * 默认 {@code false}（预生成行为）。
     */
    default boolean lazilyGenerated(String worldName) {
        return false;
    }
}

package org.windy.playershelter.runtime.api;

import org.bukkit.plugin.Plugin;
import org.windy.playershelter.api.terrain.TerrainProvider;
import org.windy.playershelter.api.terrain.TerrainRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 地形生成 provider 注册表（apiVersion 7）。
 *
 * <p>主插件本体不再引用任何地形引擎。建 NATURAL 世界时逐个询问 provider：
 * 第一个 {@code supports()} 返回 true 的负责生成，全部不认领则调用方回退原版 vanilla。
 *
 * <p>线程安全：注册/注销在 onEnable 主线程发生，查询在建世界的异步线程——故查询走快照副本。
 * 归属插件被禁用时其 provider 自动跳过（不要求显式注销，避免插件崩溃后留下僵尸 provider）。
 */
public final class TerrainProviderRegistry {

    private record Entry(Plugin plugin, TerrainProvider provider) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void register(Plugin plugin, TerrainProvider provider) {
        if (plugin == null || provider == null) {
            throw new IllegalArgumentException("plugin/provider required");
        }
        entries.add(new Entry(plugin, provider));
        // 数值小的先问；同优先级按注册顺序（List.sort 稳定）。
        entries.sort(Comparator.comparingInt(e -> e.provider().priority()));
    }

    public synchronized void unregister(Plugin plugin) {
        entries.removeIf(e -> e.plugin().equals(plugin));
    }

    /**
     * 找出认领该请求的 provider。
     *
     * <p>provider 的 {@code supports()} 抛异常按「不适用」处理并继续问下一个——扩展出问题不该阻断
     * 世界的创建流程。
     *
     * @return 认领者；无人认领返回 {@code null}（调用方回退 vanilla）
     */
    public TerrainProvider select(TerrainRequest request) {
        for (Entry entry : snapshot()) {
            if (!entry.plugin().isEnabled()) {
                continue;
            }
            try {
                if (entry.provider().supports(request)) {
                    return entry.provider();
                }
            } catch (Throwable ignored) {
                // 认领判定失败 → 视为不适用
            }
        }
        return null;
    }

    /**
     * 该世界是否被任一 provider 声明为惰性生成。
     *
     * @return true 表示扩界后不应立刻预生成
     */
    public boolean lazilyGenerated(String worldName) {
        for (Entry entry : snapshot()) {
            if (!entry.plugin().isEnabled()) {
                continue;
            }
            try {
                if (entry.provider().lazilyGenerated(worldName)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 忽略单个 provider 的判定失败
            }
        }
        return false;
    }

    /** 是否有可用的 provider（日志/排障用）。 */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    private synchronized List<Entry> snapshot() {
        return List.copyOf(entries);
    }
}

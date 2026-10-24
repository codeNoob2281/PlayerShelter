package org.windy.playershelter.service;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 实体/机器限额（决策 P1：自动化开放但防卡）。上限唯一来源是 levels.yml 的 {@code levels.<等级>.limits}
 * 显式值（该文件打进 jar 随插件释放，本身就是兜底）；某级缺某项时向下取最近一个写了该项的等级。
 * 纯数据/算法，平台无关；计数与拦截在适配层（{@code ShelterCensus} / 监听器）。
 *
 * <p>机器配额：只有 levels.yml {@code limits.machines} 里列出的方块 id 才受单独限制（其余只计入方块实体总额）。
 *
 * @param maxLevel           等级上限（钳制）
 * @param capsByLevel        等级 → 生物/方块实体/掉落物/载具上限
 * @param machineCapsByLevel 等级 → (机器方块 id → 配额)
 */
public record EntityLimits(int maxLevel,
                           Map<Integer, Caps> capsByLevel,
                           Map<Integer, Map<String, Integer>> machineCapsByLevel) {

    /** 完全未配置时的取值：不限制（配置随 jar 释放，缺失只可能是配置事故，此时不拦玩家）。 */
    private static final int UNLIMITED = Integer.MAX_VALUE / 4;

    public record Caps(Integer mobs, Integer tiles, Integer drops, Integer vehicles) {}

    public EntityLimits {
        capsByLevel = capsByLevel == null ? Map.of() : Map.copyOf(capsByLevel);
        machineCapsByLevel = machineCapsByLevel == null ? Map.of() : Map.copyOf(machineCapsByLevel);
    }

    private int clampLevel(int level) {
        return Math.max(1, Math.min(level, maxLevel));
    }

    /** 从 {@code level} 向下找最近一个定义了该组件的等级；都没定义 → 不限制。 */
    private int cap(int level, ToIntFunction<Caps> component) {
        for (int lv = clampLevel(level); lv >= 1; lv--) {
            Caps c = capsByLevel.get(lv);
            if (c != null) {
                int v = component.applyAsInt(c);
                if (v >= 0) {
                    return v;
                }
            }
        }
        return UNLIMITED;
    }

    private static int nn(Integer v) {
        return v == null ? -1 : Math.max(0, v);
    }

    public int mobCap(int level) {
        return cap(level, c -> nn(c.mobs()));
    }

    public int tileCap(int level) {
        return cap(level, c -> nn(c.tiles()));
    }

    public int dropCap(int level) {
        return cap(level, c -> nn(c.drops()));
    }

    public int vehicleCap(int level) {
        return cap(level, c -> nn(c.vehicles()));
    }

    /** 该方块 id 是否受单独机器配额约束。 */
    public boolean isMachine(String blockId) {
        return machineCapsByLevel.values().stream().anyMatch(m -> m.containsKey(blockId));
    }

    /** 该机器在给定等级的配额；非机器返回 {@code -1}（不单独限制）。向下取最近定义，等级低于首个定义时取最小定义等级。 */
    public int machineCap(String blockId, int level) {
        int lv = clampLevel(level);
        for (int i = lv; i >= 1; i--) {
            Map<String, Integer> m = machineCapsByLevel.get(i);
            if (m != null && m.containsKey(blockId)) {
                return Math.max(0, m.get(blockId));
            }
        }
        for (int i = lv + 1; i <= maxLevel; i++) {
            Map<String, Integer> m = machineCapsByLevel.get(i);
            if (m != null && m.containsKey(blockId)) {
                return Math.max(0, m.get(blockId));
            }
        }
        return -1;
    }

    /** 关闭全部限额的哨兵（空配置即不限制）；测试/默认兜底用。 */
    public static EntityLimits unlimited() {
        return new EntityLimits(1, Map.of(), Map.of());
    }
}

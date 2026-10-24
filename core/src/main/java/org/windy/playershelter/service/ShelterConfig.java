package org.windy.playershelter.service;

import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ItemCost;

import java.util.List;
import java.util.Map;

/**
 * 全部可调参数的不可变快照（决策：可调数值一律 config 键 + 默认值，core 不挑魔数）。
 * 适配层从 config.yml / levels.yml 读出后构造，传给各 service。默认值见 {@link #defaults()}。
 *
 * <p>随等级变化的数值（名额、升级费用、边界）唯一来源是 levels.yml 的显式按级配置
 * （该文件打进 jar 随插件释放，本身就是兜底）；某级缺省时向下取最近一个写了的等级。
 *
 * @param layout              固定物理地块 + 每级解锁额度（新世界冻结这份）
 * @param idleUnloadMinutes   世界内零玩家后多久 save+unload（决策 #7，默认 10）
 * @param maxLoadedWorlds     同时加载世界数上限，超出 LRU 淘汰（决策 #53）
 * @param inactiveDeleteDays  玩家多少天不上线进入「存续期」（决策 #6）
 * @param inactiveGraceDays   存续期时长；期间任意登录重置计时，过期才删（决策 #6/#28）
 * @param resetCooldownHours  reset/重建冷却（决策 #77）
 * @param resetMaxPerDay      每天最多 reset 次数（决策 #77）
 * @param publicMinLevel      上公开目录所需最低等级门槛（决策 #71）
 * @param defaultServerName   本后端服名（决策 #59 世界绑服；单服填本服名）
 */
public record ShelterConfig(ShelterLayout layout,
                            int idleUnloadMinutes,
                            int maxLoadedWorlds,
                            int inactiveDeleteDays,
                            int inactiveGraceDays,
                            int resetCooldownHours,
                            int resetMaxPerDay,
                            int publicMinLevel,
                            String defaultServerName,
                            Map<Integer, Double> upgradeCostsByTargetLevel,
                            Map<Integer, List<ItemCost>> upgradeItemsByTargetLevel,
                            Map<Integer, Integer> adminCapsByLevel,
                            Map<Integer, Integer> trustCapsByLevel) {

    public ShelterConfig {
        upgradeCostsByTargetLevel = upgradeCostsByTargetLevel == null ? Map.of() : Map.copyOf(upgradeCostsByTargetLevel);
        upgradeItemsByTargetLevel = upgradeItemsByTargetLevel == null ? Map.of() : Map.copyOf(upgradeItemsByTargetLevel);
        adminCapsByLevel = adminCapsByLevel == null ? Map.of() : Map.copyOf(adminCapsByLevel);
        trustCapsByLevel = trustCapsByLevel == null ? Map.of() : Map.copyOf(trustCapsByLevel);
    }

    public ShelterConfig(ShelterLayout layout,
                         int idleUnloadMinutes,
                         int maxLoadedWorlds,
                         int inactiveDeleteDays,
                         int inactiveGraceDays,
                         int resetCooldownHours,
                         int resetMaxPerDay,
                         int publicMinLevel,
                         String defaultServerName) {
        this(layout, idleUnloadMinutes, maxLoadedWorlds, inactiveDeleteDays, inactiveGraceDays,
                resetCooldownHours, resetMaxPerDay, publicMinLevel, defaultServerName,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private int clampLevel(int level) {
        return Math.max(1, Math.min(level, layout.maxLevel()));
    }

    /** 向下取最近一个定义了的等级；等级低于首个定义时取最小定义等级；全空 → 0。 */
    private static int capAt(Map<Integer, Integer> capsByLevel, int level) {
        for (int lv = level; lv >= 1; lv--) {
            Integer v = capsByLevel.get(lv);
            if (v != null) {
                return Math.max(0, v);
            }
        }
        Integer best = null;
        for (Map.Entry<Integer, Integer> e : capsByLevel.entrySet()) {
            if (best == null || e.getKey() < best) {
                best = e.getKey();
            }
        }
        return best == null ? 0 : Math.max(0, capsByLevel.get(best));
    }

    /** 给定等级的副主人名额上限（决策 P1 随等级增长）。 */
    public int adminCapAt(int level) {
        return capAt(adminCapsByLevel, clampLevel(level));
    }

    /** 给定等级的共建 trust 名额上限（决策 P1 随等级增长）。 */
    public int trustCapAt(int level) {
        return capAt(trustCapsByLevel, clampLevel(level));
    }

    /** 第 {@code fromLevel} → {@code fromLevel+1} 级的升级费用（决策 #41）；levels.yml 未写该级 = 免费。 */
    public double upgradeCost(int fromLevel) {
        Double explicit = upgradeCostsByTargetLevel.get(clampLevel(fromLevel + 1));
        return explicit == null ? 0 : Math.max(0, explicit);
    }

    /** Items required for {@code fromLevel -> fromLevel + 1}. */
    public List<ItemCost> upgradeItems(int fromLevel) {
        return upgradeItemsByTargetLevel.getOrDefault(clampLevel(fromLevel + 1), List.of());
    }

    /** 合理默认值（仅兜底/测试；正式值由 config.yml / levels.yml 覆盖）。 */
    public static ShelterConfig defaults() {
        return new ShelterConfig(
                defaultLayout(),
                10,   // idle 10 分钟
                8,    // 同时最多 8 个世界加载
                30,   // 30 天不上线进存续期
                7,    // 存续期 7 天
                12,   // reset 冷却 12 小时
                2,    // 每天最多 reset 2 次
                3,    // 公开需 ≥3 级
                ""    // 单服默认空服名
        );
    }

    private static ShelterLayout defaultLayout() {
        Map<Integer, Integer> sides = new java.util.LinkedHashMap<>();
        sides.put(1, 6);
        sides.put(2, 7);
        sides.put(3, 8);
        sides.put(4, 8);
        sides.put(5, 9);
        sides.put(6, 9);
        sides.put(7, 10);
        sides.put(8, 10);
        sides.put(9, 11);
        sides.put(10, 11);
        sides.put(11, 12);
        sides.put(12, 12);
        sides.put(13, 12);
        sides.put(14, 13);
        sides.put(15, 13);
        sides.put(16, 13);
        sides.put(17, 14);
        sides.put(18, 14);
        sides.put(19, 14);
        sides.put(20, 15);
        return new ShelterLayout(6, 15, 20, sides);
    }
}

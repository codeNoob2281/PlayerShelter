package org.windy.playershelter.domain.model;

/**
 * 庇护所世界的生成类型（决策 #1：玩家创建时可选）。
 *
 * <ul>
 *   <li>{@link #NATURAL} —— 自然地形：默认原版 normal；若装了地形扩展（注册 TerrainProvider，如 Iris 扩展）
 *       则由扩展接管（可惰性生成）。不整地（决策 #47）。</li>
 *   <li>{@link #FLAT} —— 超平坦：纯建造画布。MC1.16+ 的 generatorSettings 必须是 JSON（见适配层）。</li>
 *   <li>{@link #VOID} —— 虚空空岛：给个初始小岛 + 横幅（决策 #45）。</li>
 * </ul>
 */
public enum GenerationType {
    NATURAL,
    FLAT,
    VOID;

    /** 解析命令参数（{@code /ps create <类型>}），大小写不敏感；无法识别返回 {@code null}。 */
    public static GenerationType parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase()) {
            case "natural", "normal", "自然" -> NATURAL;
            case "flat", "superflat", "超平坦", "平坦" -> FLAT;
            case "void", "skyblock", "island", "虚空", "空岛" -> VOID;
            default -> null;
        };
    }

    /** 该类型是否可能惰性生成（仅 NATURAL；是否真惰性由注册的 TerrainProvider 声明）。 */
    public boolean mayBeLazy() {
        return this == NATURAL;
    }
}

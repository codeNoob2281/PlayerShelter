package org.windy.playershelter.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 庇护所限额数值的<b>入库</b>规则：levels.yml 仅作默认值源，每个庇护所一份数值存库
 * （{@code Shelter.limits}，键 → 上限）。本类是键的唯一定义处，并负责「创建初始值」与
 * 「升级差值迁移」两个纯函数，admin 指令调整单项后差值迁移会保留手动调整量。
 *
 * <p>键：{@code mobs/tiles/drops/vehicles/admin/trust/machine:<方块id>}。
 * 库存缺键（旧数据）时各处回退 {@link EntityLimits} / {@link ShelterConfig} 按级现算，行为与入库前一致。
 */
public final class ShelterLimits {

    public static final String MOBS = "mobs";
    public static final String TILES = "tiles";
    public static final String DROPS = "drops";
    public static final String VEHICLES = "vehicles";
    public static final String ADMIN = "admin";
    public static final String TRUST = "trust";
    public static final String MACHINE_PREFIX = "machine:";

    private ShelterLimits() {
    }

    /** 机器配额键（{@code machine:<方块id>}，id 一律小写，与 LimitEnforcer 口径一致）。 */
    public static String machineKey(String blockId) {
        return MACHINE_PREFIX + blockId.toLowerCase();
    }

    /** 是否为合法限额键（admin 指令校验用）。 */
    public static boolean isValidKey(String key) {
        if (key == null) {
            return false;
        }
        return switch (key) {
            case MOBS, TILES, DROPS, VEHICLES, ADMIN, TRUST -> true;
            default -> key.startsWith(MACHINE_PREFIX) && key.length() > MACHINE_PREFIX.length();
        };
    }

    /** 创建/重置时的初始值：取该级 levels.yml 配置的完整快照。 */
    public static Map<String, Integer> initial(int level, EntityLimits el, ShelterConfig sc) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(MOBS, el.mobCap(level));
        out.put(TILES, el.tileCap(level));
        out.put(DROPS, el.dropCap(level));
        out.put(VEHICLES, el.vehicleCap(level));
        out.put(ADMIN, sc.adminCapAt(level));
        out.put(TRUST, sc.trustCapAt(level));
        for (String id : machineIds(el)) {
            out.put(machineKey(id), el.machineCap(id, level));
        }
        return Map.copyOf(out);
    }

    /**
     * 跨级差值迁移：对配置里出现的每个键做 {@code stored + (cfg(to) − cfg(from))}，钳 ≥0。
     * stored 缺键时以 {@code cfg(from)} 为基线（即旧数据升级后 = 新级配置值）；
     * 配置外的键（如已从 levels.yml 移除的机器）原样保留。
     */
    public static Map<String, Integer> migrate(Map<String, Integer> stored, int fromLevel, int toLevel,
                                               EntityLimits el, ShelterConfig sc) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (stored != null) {
            out.putAll(stored);
        }
        applyDelta(out, MOBS, el.mobCap(fromLevel), el.mobCap(toLevel));
        applyDelta(out, TILES, el.tileCap(fromLevel), el.tileCap(toLevel));
        applyDelta(out, DROPS, el.dropCap(fromLevel), el.dropCap(toLevel));
        applyDelta(out, VEHICLES, el.vehicleCap(fromLevel), el.vehicleCap(toLevel));
        applyDelta(out, ADMIN, sc.adminCapAt(fromLevel), sc.adminCapAt(toLevel));
        applyDelta(out, TRUST, sc.trustCapAt(fromLevel), sc.trustCapAt(toLevel));
        for (String id : machineIds(el)) {
            String key = machineKey(id);
            applyDelta(out, key, el.machineCap(id, fromLevel), el.machineCap(id, toLevel));
        }
        return Map.copyOf(out);
    }

    private static void applyDelta(Map<String, Integer> out, String key, int cfgFrom, int cfgTo) {
        int base = out.getOrDefault(key, cfgFrom);
        out.put(key, Math.max(0, base + (cfgTo - cfgFrom)));
    }

    /** 配置里出现过的全部机器 id（任一等级定义即算），排序保证序列化稳定。 */
    private static java.util.Set<String> machineIds(EntityLimits el) {
        TreeSet<String> ids = new TreeSet<>();
        for (Map<String, Integer> m : el.machineCapsByLevel().values()) {
            ids.addAll(m.keySet());
        }
        return ids;
    }
}

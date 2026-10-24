package org.windy.playershelter;

import org.junit.jupiter.api.Test;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.service.EntityLimits;
import org.windy.playershelter.service.ShelterConfig;
import org.windy.playershelter.service.ShelterLimits;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterLimitsTest {

    private EntityLimits entityLimits() {
        return new EntityLimits(20,
                Map.of(
                        1, new EntityLimits.Caps(60, 200, 100, 20),
                        3, new EntityLimits.Caps(80, 240, null, null)),
                Map.of(1, Map.of("minecraft:hopper", 32)));
    }

    private ShelterConfig shelterConfig() {
        return new ShelterConfig(new ShelterLayout(6, 15, 20),
                10, 8, 30, 7, 12, 2, 3, "",
                Map.of(), Map.of(),
                Map.of(1, 3, 3, 5),
                Map.of(1, 5));
    }

    @Test
    void initialTakesLevelSnapshot() {
        Map<String, Integer> init = ShelterLimits.initial(1, entityLimits(), shelterConfig());
        assertEquals(60, init.get(ShelterLimits.MOBS));
        assertEquals(200, init.get(ShelterLimits.TILES));
        assertEquals(100, init.get(ShelterLimits.DROPS));
        assertEquals(20, init.get(ShelterLimits.VEHICLES));
        assertEquals(3, init.get(ShelterLimits.ADMIN));
        assertEquals(5, init.get(ShelterLimits.TRUST));
        assertEquals(32, init.get(ShelterLimits.machineKey("minecraft:hopper")));
    }

    @Test
    void upgradeAddsDeltaAndKeepsManualAdjustments() {
        EntityLimits el = entityLimits();
        ShelterConfig sc = shelterConfig();
        // 库存值被 admin 手动调高过（mobs 60→99），升级只加差值，手动调整量保留。
        Map<String, Integer> stored = new java.util.HashMap<>(ShelterLimits.initial(1, el, sc));
        stored.put(ShelterLimits.MOBS, 99);
        Map<String, Integer> up = ShelterLimits.migrate(stored, 1, 3, el, sc);
        assertEquals(99 + (80 - 60), up.get(ShelterLimits.MOBS));       // 差值累加
        assertEquals(200 + (240 - 200), up.get(ShelterLimits.TILES));
        assertEquals(100, up.get(ShelterLimits.DROPS));                 // 3 级未定义 → 向下取 1 级，差值 0
        assertEquals(3 + (5 - 3), up.get(ShelterLimits.ADMIN));
        assertEquals(5, up.get(ShelterLimits.TRUST));                   // 配置未变 → 不动
    }

    @Test
    void downgradeNegativeDeltaClampsAtZero() {
        EntityLimits el = entityLimits();
        ShelterConfig sc = shelterConfig();
        // 库存值被人为调低到 10，降级差值 -20 → 钳到 0 而不是负数。
        Map<String, Integer> up = ShelterLimits.migrate(Map.of(ShelterLimits.MOBS, 10), 3, 1, el, sc);
        assertEquals(0, up.get(ShelterLimits.MOBS));
        // 降级同样按差值走：3 级 240 → 1 级 200。
        assertEquals(200, up.get(ShelterLimits.TILES));
    }

    @Test
    void missingStoredKeyFallsBackToOldLevelConfigAsBaseline() {
        EntityLimits el = entityLimits();
        ShelterConfig sc = shelterConfig();
        // 旧数据（库存为空）升级后 = 新级配置值。
        Map<String, Integer> up = ShelterLimits.migrate(Map.of(), 1, 3, el, sc);
        assertEquals(el.mobCap(3), up.get(ShelterLimits.MOBS));
        assertEquals(el.tileCap(3), up.get(ShelterLimits.TILES));
        assertEquals(sc.adminCapAt(3), up.get(ShelterLimits.ADMIN));
        assertEquals(sc.trustCapAt(3), up.get(ShelterLimits.TRUST));
        assertEquals(el.machineCap("minecraft:hopper", 3), up.get(ShelterLimits.machineKey("minecraft:hopper")));
    }

    @Test
    void unknownStoredKeysAreCarriedOver() {
        // 配置外的键（如已从 levels.yml 移除的机器）原样保留，不被差值迁移清掉。
        Map<String, Integer> up = ShelterLimits.migrate(
                Map.of("machine:minecraft:removed_machine", 7), 1, 3, entityLimits(), shelterConfig());
        assertEquals(7, up.get("machine:minecraft:removed_machine"));
    }

    @Test
    void keyValidation() {
        assertTrue(ShelterLimits.isValidKey("mobs"));
        assertTrue(ShelterLimits.isValidKey("machine:minecraft:hopper"));
        assertFalse(ShelterLimits.isValidKey("machine:"));
        assertFalse(ShelterLimits.isValidKey("nonsense"));
        assertEquals("machine:minecraft:hopper", ShelterLimits.machineKey("Minecraft:HOPPER"));
    }
}

package org.windy.playershelter;

import org.junit.jupiter.api.Test;
import org.windy.playershelter.service.EntityLimits;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLimitsTest {

    private EntityLimits sample() {
        return new EntityLimits(50,
                Map.of(
                        1, new EntityLimits.Caps(60, 200, 100, 20),
                        5, new EntityLimits.Caps(80, null, null, null),
                        10, new EntityLimits.Caps(null, 400, 180, 40)),
                Map.of(
                        1, Map.of("minecraft:hopper", 32),
                        5, Map.of("minecraft:hopper", 36)));
    }

    @Test
    void capsComeFromExplicitLevels() {
        EntityLimits l = sample();
        assertEquals(60, l.mobCap(1));
        assertEquals(80, l.mobCap(5));
        assertEquals(80, l.mobCap(9));   // 9 级未定义 → 向下取 5 级
        assertEquals(400, l.tileCap(10));
        assertEquals(200, l.tileCap(5)); // 5 级没写 tiles → 向下取 1 级的 200
        assertEquals(40, l.vehicleCap(10));
    }

    @Test
    void capsClampAtMaxLevel() {
        EntityLimits l = sample();
        assertEquals(80, l.mobCap(999)); // 钳到 50 级 → 向下取 5 级
    }

    @Test
    void machineQuotaByBlockId() {
        EntityLimits l = sample();
        assertTrue(l.isMachine("minecraft:hopper"));
        assertFalse(l.isMachine("minecraft:stone"));
        assertEquals(32, l.machineCap("minecraft:hopper", 1));
        assertEquals(36, l.machineCap("minecraft:hopper", 5));
        assertEquals(36, l.machineCap("minecraft:hopper", 7)); // 向下取 5 级
        assertEquals(-1, l.machineCap("minecraft:stone", 1));  // 非机器 = 不限
    }

    @Test
    void unlimitedSentinelIsHuge() {
        EntityLimits u = EntityLimits.unlimited();
        assertTrue(u.mobCap(1) > 1_000_000);
        assertFalse(u.isMachine("minecraft:hopper"));
    }

    @Test
    void shelterCapsComeFromExplicitLevels() {
        // 名额随等级（决策 P1）：显式按级配置，缺级向下取最近。
        org.windy.playershelter.service.ShelterConfig c = new org.windy.playershelter.service.ShelterConfig(
                new org.windy.playershelter.domain.model.ShelterLayout(64, 16, 10),
                10, 8, 30, 7, 12, 2, 3, "",
                Map.of(), Map.of(),
                Map.of(1, 3, 5, 7),
                Map.of(1, 5, 5, 13));
        assertEquals(3, c.adminCapAt(1));
        assertEquals(7, c.adminCapAt(5));
        assertEquals(7, c.adminCapAt(9));   // 向下取 5 级
        assertEquals(5, c.trustCapAt(1));
        assertEquals(13, c.trustCapAt(5));
        assertEquals(7, c.adminCapAt(999)); // 钳到 maxLevel 10 → 向下取 5 级
    }
}

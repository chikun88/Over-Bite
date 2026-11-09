package com.chihaya.mu3androidtx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowBatteryTriggerTest {

    @Test
    fun firesOnceWhenDroppingBelowThreshold() {
        val t = LowBatteryTrigger(30)
        assertFalse(t.update(31, charging = false))
        assertFalse(t.update(30, charging = false))
        assertTrue(t.update(29, charging = false))
        assertFalse(t.update(29, charging = false))
        assertFalse(t.update(25, charging = false))
    }

    @Test
    fun doesNotFireWhenAlreadyLowAtStart() {
        val t = LowBatteryTrigger(30)
        assertFalse(t.update(20, charging = false))
        assertFalse(t.update(19, charging = false))
    }

    @Test
    fun rearmsAfterRecoveringAboveThreshold() {
        val t = LowBatteryTrigger(30)
        t.update(30, charging = false)
        assertTrue(t.update(29, charging = false))
        assertFalse(t.update(31, charging = true))
        assertFalse(t.update(30, charging = false))
        assertTrue(t.update(29, charging = false))
    }

    @Test
    fun doesNotFireWhileCharging() {
        val t = LowBatteryTrigger(30)
        t.update(30, charging = true)
        assertFalse(t.update(29, charging = true))
    }

    @Test
    fun firesAfterUnpluggingOnlyWhenLevelCrosses() {
        val t = LowBatteryTrigger(30)
        t.update(28, charging = true)
        // 抜いた時点ですでに 30% 未満（「切った」瞬間ではない）→ 鳴らさない。
        assertFalse(t.update(28, charging = false))
        val fires = listOf(t.update(27, charging = false), t.update(26, charging = false))
        assertEquals(listOf(false, false), fires)
    }
}

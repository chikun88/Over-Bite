package com.chihaya.mu3androidtx

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowBatteryTriggerTest {

    @Test
    fun firesAtStartWhenAlreadyAtOrBelowThreshold() {
        assertTrue(LowBatteryTrigger(30).update(20))
        assertTrue(LowBatteryTrigger(30).update(30))
    }

    @Test
    fun doesNotFireAtStartWhenAboveThreshold() {
        assertFalse(LowBatteryTrigger(30).update(31))
    }

    @Test
    fun firesOnceWhenDroppingToThreshold() {
        val t = LowBatteryTrigger(30)
        assertFalse(t.update(32))
        assertFalse(t.update(31))
        assertTrue(t.update(30))
        assertFalse(t.update(29))
        assertFalse(t.update(25))
    }

    @Test
    fun doesNotRepeatAfterStartupAlert() {
        val t = LowBatteryTrigger(30)
        assertTrue(t.update(25))
        assertFalse(t.update(25))
        assertFalse(t.update(24))
    }

    @Test
    fun rearmsAfterRecoveringAboveThreshold() {
        val t = LowBatteryTrigger(30)
        assertTrue(t.update(28))
        assertFalse(t.update(31))
        assertTrue(t.update(30))
    }
}

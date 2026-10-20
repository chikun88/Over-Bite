package com.chihaya.mu3androidtx
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class AnglePresetCommandTest {
    @Test fun allFivePresetsAreDistinctAndNeverNavigationOrPressure() {
        val c = AnglePresetCommand(); val frame=ByteArray(7)
        for (id in 1..5) {
            c.request(id,0); assertTrue(c.fill(frame,100))
            val b=frame[6].toInt() and 255
            assertEquals(id,(b shr 3) and 7)
            assertEquals(0x40,b and 0xC7)
            assertEquals(0,b and 0x87)
            assertTrue((0..3).all { (frame[it].toInt() and 255)==128 })
            assertEquals(0,frame[4].toInt()); assertEquals(0,frame[5].toInt())
        }
    }
    @Test fun expiresAndStopCancelsImmediately() {
        val c=AnglePresetCommand(); val f=ByteArray(7)
        c.request(1,0); assertTrue(c.fill(f,299)); assertFalse(c.fill(f,300))
        c.request(5,500); c.stop(); assertFalse(c.fill(f,510))
    }
}

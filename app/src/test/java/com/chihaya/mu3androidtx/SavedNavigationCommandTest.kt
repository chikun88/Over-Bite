package com.chihaya.mu3androidtx

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The saved-pose frames are the only thing this app can send that makes the
 * robot drive itself, so the byte on the wire is checked here rather than only
 * on a field day.
 *
 * Decoder contract, from the gateway's Mu3Navigation::receive:
 *   command  = payload[0..3]==128, payload[4..5]==0, (payload[6] & 0xC1)==0xC0
 *   slot     = (payload[6] >> 1) & 15
 */
class SavedNavigationCommandTest {

    private fun SavedNavigationCommand.send(out: ByteArray, now: Long, operatorInput: Boolean = false): Boolean {
        val filled = fill(out, now, operatorInput)
        if (filled) onTransmitted(out, now)
        return filled
    }

    private fun frameFor(slot: Int, atMs: Long = 600L): ByteArray {
        val command = SavedNavigationCommand()
        command.request(slot, 0L)
        val out = ByteArray(7)
        for (t in listOf(0L, 20L, 40L, 60L)) command.send(out, t)
        assertTrue(command.send(out, atMs))
        return out
    }

    private fun decodeSlot(payload: ByteArray): Int {
        val marker = payload[6].toInt() and 0xFF
        val neutral = (0..3).all { (payload[it].toInt() and 0xFF) == 128 } &&
            payload[4].toInt() == 0 && payload[5].toInt() == 0
        if (!neutral || (marker and 0x81) != 0x80) return 0
        return ((marker shr 1) and 15) or (if ((marker and 0x40) == 0) 16 else 0)
    }

    @Test
    fun everyRadioSlotSurvivesTheGatewayDecoder() {
        for (slot in 1..31) {
            val payload = frameFor(slot)
            assertArrayEquals(
                byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 128.toByte(), 0, 0),
                payload.copyOfRange(0, 6),
            )
            assertEquals(slot, decodeSlot(payload))
        }
    }

    @Test
    fun bothFieldsMapEveryPointToADistinctDecodableSlot() {
        val seen = mutableSetOf<Int>()
        for (point in SAVED_NAVIGATION_POINTS) {
            for (offset in listOf(0, SAVED_NAVIGATION_SIDE_SLOT_OFFSET)) {
                val slot = point.slot + offset
                assertTrue(slot in 1..31, "slot $slot out of radio range")
                assertTrue(seen.add(slot), "duplicate slot $slot")
                assertEquals(slot, decodeSlot(frameFor(slot)))
            }
        }
        assertEquals(SAVED_NAVIGATION_POINTS.size * 2, seen.size)
    }

    @Test
    fun leftAndRightSlotsAreNeverConfusedForEachOther() {
        for (point in SAVED_NAVIGATION_POINTS) {
            val left = decodeSlot(frameFor(point.slot))
            val right = decodeSlot(frameFor(point.slot + SAVED_NAVIGATION_SIDE_SLOT_OFFSET))
            assertEquals(point.slot, left)
            assertEquals(point.slot + SAVED_NAVIGATION_SIDE_SLOT_OFFSET, right)
            assertNotEquals(left, right)
        }
    }

    @Test
    fun aTapFirstSendsAReleaseSoTheGatewayCannotReplayTheHeldSlot() {
        val command = SavedNavigationCommand()
        command.request(9, 0L)
        val out = ByteArray(7)
        for (t in listOf(0L, 20L, 40L, 60L, 100L)) command.send(out, t)
        assertEquals(0, decodeSlot(out))
        command.send(out, 600L)
        assertEquals(9, decodeSlot(out))
    }

    @Test
    fun tappingAgainFlipsTheNonceSoTheGatewayCountsASecondRequest() {
        val command = SavedNavigationCommand()
        val first = ByteArray(7)
        command.request(4, 0L)
        for (t in listOf(0L, 20L, 40L, 60L, 600L)) command.send(first, t)
        val second = ByteArray(7)
        command.request(4, 1000L)
        for (t in listOf(1000L, 1020L, 1040L, 1060L, 1600L)) command.send(second, t)
        assertEquals(decodeSlot(first), decodeSlot(second))
        assertNotEquals(first[6], second[6])
    }

    @Test
    fun stopRepeatsExplicitReleaseThenReturnsToManual() {
        val command = SavedNavigationCommand()
        command.request(12, 0L)
        command.stop()
        val out = ByteArray(7)
        for (t in listOf(600L, 620L, 640L, 660L, 700L)) {
            assertTrue(command.send(out, t))
            assertEquals(0, decodeSlot(out))
        }
        assertFalse(command.send(out, 720L))
        assertFalse(command.transmitting)
    }

    @Test
    fun delayedSenderStillTransmitsReleaseBeforeDestination() {
        val command = SavedNavigationCommand()
        command.request(2, 0L)
        val out = ByteArray(7)
        for (t in listOf(2000L, 2020L, 2040L, 2060L)) {
            assertTrue(command.send(out, t))
            assertEquals(0, decodeSlot(out))
        }
        command.send(out, 2120L)
        assertEquals(2, decodeSlot(out))
    }

    @Test
    fun cancelDuringReleaseNeverSendsTheOldDestination() {
        val command = SavedNavigationCommand()
        val out = ByteArray(7)
        command.request(12, 0L)
        command.send(out, 0L)
        command.stop()
        for (t in 20L..200L step 20L) {
            if (command.send(out, t)) assertEquals(0, decodeSlot(out))
        }
        assertEquals(0, command.slot)
    }

    @Test
    fun slotOrderMatchesTheAutomationSideSavedPoseNames() {
        // Must stay identical to saved_pose_names in the automation side's
        // config/mu3_navigation.yaml; the radio only carries the index.
        assertEquals(
            listOf("A", "BAKETU2", "BAKETU3", "旗上側", "旗下側", "退避位置", "装填位置", "机保護1", "机保護2", "バケツ後2", "バケツ後3", "IDOU"),
            SAVED_NAVIGATION_POINTS.map { it.jetsonName },
        )
        assertEquals((1..7).toList() + (17..20).toList() + 23, SAVED_NAVIGATION_POINTS.map { it.slot })
    }

    @Test
    fun schematicButtonsRemainWithinTheFieldAndDoNotOverlap() {
        for (point in SAVED_NAVIGATION_POINTS) {
            assertTrue(point.x in 0.05..0.95)
            assertTrue(point.y in 0.05..0.95)
        }
        for (a in SAVED_NAVIGATION_POINTS) for (b in SAVED_NAVIGATION_POINTS) {
            if (a == b) continue
            assertTrue(kotlin.math.abs(a.x - b.x) > .075 || kotlin.math.abs(a.y - b.y) > .16)
        }
    }

    @Test
    fun mechanismsAndR2PassThroughWithoutCancelingTheDestination() {
        val command = SavedNavigationCommand()
        val scratch = ByteArray(7)
        command.request(12, 0L)
        for (t in listOf(0L, 20L, 40L, 60L, 120L)) command.send(scratch, t)
        for (byte in 4..6) for (bit in 0..7) {
            val payload = byteArrayOf(-128, -128, -128, -128, 0, 0, 0)
            payload[byte] = (1 shl bit).toByte()
            val before = payload.copyOf()
            assertFalse(command.send(payload, 200L, SavedNavigationCommand.hasOperatorInput(payload)))
            assertArrayEquals(before, payload)
            assertEquals(12, command.slot)
        }
        // Dedicated angle preset also passes through unchanged.
        val angle = byteArrayOf(-128, -128, -128, -128, 0, 0, 0x48)
        assertFalse(command.send(angle, 300L, SavedNavigationCommand.hasOperatorInput(angle)))
        assertEquals(0x48, angle[6].toInt())
        val neutral = byteArrayOf(-128, -128, -128, -128, 0, 0, 0)
        assertTrue(command.send(neutral, 320L, SavedNavigationCommand.hasOperatorInput(neutral)))
        assertEquals(12, decodeSlot(neutral))
    }

    @Test
    fun explicitCancelStillOverridesOperatorInput() {
        val command = SavedNavigationCommand()
        command.request(2, 0L)
        command.stop()
        val heldR2 = byteArrayOf(-128, -128, -128, -128, 1, 0, 0)
        assertTrue(command.send(heldR2, 100L, true))
        assertEquals(0, decodeSlot(heldR2))
        assertEquals(0, command.slot)
    }

    @Test
    fun angleSelectionInterleavesHeldR2AndItsReleaseWithoutCancel() {
        val command = SavedNavigationCommand()
        val angle = AnglePresetCommand()
        val mux = NavigationRadioMux()
        command.request(3, 0L)
        val scratch = ByteArray(7)
        for (t in listOf(0L, 20L, 40L, 60L, 120L)) command.send(scratch, t)
        angle.request(2, 200L)
        for (index in 0..9) {
            val held = index < 5
            val payload = byteArrayOf(-128, -128, -128, -128, if (held) 1 else 0, 0, 0)
            mux.fill(payload, 200L + index * 20L, angle, command)
            if (index % 4 == 0) assertEquals(3, decodeSlot(payload))
            else if (index % 2 == 0) assertEquals(0x50, payload[6].toInt())
            else assertEquals(if (held) 1 else 0, payload[4].toInt())
            assertEquals(3, command.slot)
        }
    }

    @Test
    fun resetSlotsCannotSelectAnySavedDestination() {
        for (slot in listOf(SavedNavigationCommand.RESET_START_LEFT, SavedNavigationCommand.RESET_START_RIGHT)) {
            assertEquals(slot, decodeSlot(frameFor(slot)))
            assertEquals(0, frameFor(slot)[6].toInt() and 1)
            for (point in SAVED_NAVIGATION_POINTS) {
                assertNotEquals(point.slot, slot)
                assertNotEquals(point.slot + SAVED_NAVIGATION_SIDE_SLOT_OFFSET, slot)
            }
        }
    }

    @Test
    fun heldMechanismsCannotStarveTheFirstDestinationOrItsRetries() {
        for (buttonByte in 4..6) for (bit in 0..7) {
            if (buttonByte == 6 && bit == 0) continue // emergency stop
            val command = SavedNavigationCommand()
            val mux = NavigationRadioMux()
            val angle = AnglePresetCommand()
            command.request(29, 0L)
            var destinations = 0
            for (index in 0..24) {
                val input = byteArrayOf(-128, -128, -128, -128, 0, 0, 0)
                input[buttonByte] = (1 shl bit).toByte()
                val original = input.copyOf()
                mux.fill(input, index * 20L, angle, command)
                command.onTransmitted(input, index * 20L)
                if (index % 2 == 1) assertArrayEquals(original, input)
                else if (index >= 8) {
                    assertEquals(29, decodeSlot(input))
                    destinations++
                }
            }
            assertTrue(destinations >= 8)
        }
    }

    @Test
    fun emergencyStopIsNeverReplacedByNavigationOrAngle() {
        val command = SavedNavigationCommand()
        val angle = AnglePresetCommand()
        val mux = NavigationRadioMux()
        command.request(2, 0L)
        angle.request(1, 0L)
        for (index in 0..20) {
            val input = byteArrayOf(-128, -128, -128, -128, 0, 0, 1)
            mux.fill(input, index * 20L, angle, command)
            assertEquals(1, input[6].toInt())
        }
    }

    @Test
    fun urgentTapShortensOneSecondWaitWithoutExceedingUartRate() {
        assertEquals(1_000L, RadioTxTiming.deadline(1_000L, 0L, 20L, false))
        assertEquals(20L, RadioTxTiming.deadline(1_000L, 0L, 20L, true))
        assertEquals(35L, RadioTxTiming.deadline(1_000L, 0L, 35L, true))
        assertEquals(10L, RadioTxTiming.deadline(10L, 0L, 20L, true))
    }

    @Test
    fun uartFloorKeepsFramesWithinTheWireTimeAndAllowsNavigationFrom9600() {
        // @DT0E + 14 HEX + CRLF = 21 bytes, 10 bits each on the wire.
        assertEquals(10L, RadioTxTiming.minimumIntervalMs(57600, 21, 6L))
        assertEquals(17L, RadioTxTiming.minimumIntervalMs(19200, 21, 6L))
        assertEquals(28L, RadioTxTiming.minimumIntervalMs(9600, 21, 6L))
        assertTrue(RadioTxTiming.minimumIntervalMs(4800, 21, 6L) > 40L)
        assertEquals(181L, RadioTxTiming.minimumIntervalMs(1200, 21, 6L))
    }

    @Test
    fun buttonEdgesGoOutEarlyButNeverFasterThanHalfThePeriodOrTheUart() {
        assertEquals(25L, RadioTxTiming.edgeGapMs(50L, 17L))
        assertEquals(17L, RadioTxTiming.edgeGapMs(20L, 17L))
        assertEquals(28L, RadioTxTiming.edgeGapMs(30L, 28L))
        // An edge 30ms after a frame on a 50ms period is sent at once.
        assertEquals(25L, RadioTxTiming.deadline(50L, 0L, RadioTxTiming.edgeGapMs(50L, 17L), true))
    }

    @Test
    fun failedWritesCannotConsumeTheReleaseHandshake() {
        val command = SavedNavigationCommand()
        val out = ByteArray(7)
        command.request(3, 0L)
        for (t in 0L..1000L step 40L) {
            command.fill(out, t) // USB write throws: no confirmation
            assertEquals(0, decodeSlot(out))
        }
        for (t in listOf(1040L, 1080L, 1120L, 1160L)) {
            command.send(out, t)
            assertEquals(0, decodeSlot(out))
        }
        command.send(out, 1200L)
        assertEquals(3, decodeSlot(out))
    }

    @Test
    fun previousTapWriteCannotAcknowledgeANewRelease() {
        val command = SavedNavigationCommand()
        val old = ByteArray(7)
        command.request(2, 0L)
        command.fill(old, 0L)
        command.request(3, 10L)
        for (t in 20L..200L step 20L) command.onTransmitted(old, t)
        val current = ByteArray(7)
        command.fill(current, 240L)
        assertEquals(0, decodeSlot(current))
    }
}

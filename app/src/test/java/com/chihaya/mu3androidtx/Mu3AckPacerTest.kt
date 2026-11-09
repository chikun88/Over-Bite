package com.chihaya.mu3androidtx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mu3AckPacerTest {
    private val ms = 1_000_000L

    private fun feed(p: Mu3AckPacer, text: String, nowNs: Long): Boolean {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return p.onBytes(bytes, bytes.size, nowNs)
    }

    @Test fun blocksUntilDtResponse() {
        val p = Mu3AckPacer()
        assertTrue(p.ready(0))
        p.onSent(0)
        assertFalse(p.ready(10 * ms))
        assertTrue(feed(p, "*DT=0E\r\n", 42 * ms))
        assertTrue(p.ready(42 * ms))
        assertEquals(1L, p.acks)
        assertEquals(42.0, p.lastAckMs, 0.001)
    }

    @Test fun responseSplitAcrossReads() {
        val p = Mu3AckPacer()
        p.onSent(0)
        assertFalse(feed(p, "*D", 5 * ms))
        assertFalse(feed(p, "T=0", 6 * ms))
        assertTrue(feed(p, "E\r\n", 7 * ms))
    }

    @Test fun rejectionAlsoReleases() {
        val p = Mu3AckPacer()
        p.onSent(0)
        assertTrue(feed(p, "*IR=01\r\n", 3 * ms))
        assertTrue(p.ready(3 * ms))
        assertEquals(1L, p.rejects)
    }

    @Test fun otherLinesAndNoiseIgnored() {
        val p = Mu3AckPacer()
        p.onSent(0)
        assertFalse(feed(p, "\r\n*DR=0E80808080000000\r\nxx*RA=40\r\n", 3 * ms))
        assertFalse(p.ready(3 * ms))
    }

    @Test fun lostResponseTimesOutAndAdaptsToMeasuredLatency() {
        val p = Mu3AckPacer()
        p.onSent(0)
        // 実測前は上限80msまで待つ
        assertFalse(p.ready(79 * ms))
        assertTrue(p.ready(80 * ms))
        assertEquals(1L, p.timeouts)

        // 応答が30msで返るなら、打ち切りは 2*30+10 = 70ms
        var t = 100 * ms
        repeat(20) {
            p.onSent(t)
            feed(p, "*DT=0E\r\n", t + 30 * ms)
            t += 40 * ms
        }
        p.onSent(t)
        assertFalse(p.ready(t + 69 * ms))
        assertTrue(p.ready(t + 70 * ms))
    }

    @Test fun moduleWithoutResponsesFallsBackToFreeRunning() {
        val p = Mu3AckPacer(noAckTimeoutsToGiveUp = 3)
        var t = 0L
        repeat(3) {
            p.onSent(t)
            t += 80 * ms
            assertTrue(p.ready(t))
        }
        assertFalse(p.pacingActive)
        p.onSent(t)
        assertTrue(p.ready(t + 1))
        // 応答が来れば応答待ちに戻る
        feed(p, "*DT=0E\r\n", t + 2)
        assertTrue(p.pacingActive)
    }

    @Test fun lateResponseAfterTimeoutReleasesAtMostOneQueuedFrame() {
        val p = Mu3AckPacer()
        p.onSent(0)
        assertTrue(p.ready(80 * ms))
        p.onSent(80 * ms)
        // 前のフレームの遅れた応答で次の枠が空く。MU-3内に残るのは高々1フレーム
        feed(p, "*DT=0E\r\n", 90 * ms)
        assertTrue(p.ready(90 * ms))
    }

    @Test fun resetClearsOutstanding() {
        val p = Mu3AckPacer()
        p.onSent(0)
        p.reset()
        assertTrue(p.ready(1))
    }
}

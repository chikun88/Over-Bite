package com.chihaya.mu3androidtx

/** Named elevation selection; target values are calibrated on bacon6. */
class AnglePresetCommand {
    private var preset = 0
    private var until = 0L
    @Synchronized fun request(value: Int, now: Long) {
        require(value in 1..5)
        preset = value; until = now + 300L
    }
    @Synchronized fun stop() { preset = 0 }
    @Synchronized fun fill(out: ByteArray, now: Long): Boolean {
        if (preset == 0 || now >= until) { preset = 0; return false }
        for (i in 0..3) out[i] = 128.toByte()
        out[4] = 0; out[5] = 0; out[6] = (0x40 or (preset shl 3)).toByte()
        return true
    }
}

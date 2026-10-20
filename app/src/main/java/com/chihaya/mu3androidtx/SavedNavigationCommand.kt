package com.chihaya.mu3androidtx

/** The TX loop is the only writer to the radio. */
class SavedNavigationCommand {
    @Volatile var slot = 0
        private set
    private var nonce = 0
    @Volatile private var releasing = false
    private var releaseStarted = -1L
    private var releaseFrames = 0
    val transmitting: Boolean get() = slot != 0 || releasing
    val releasePending: Boolean get() = releasing

    companion object {
        const val RESET_START_LEFT = 16
        const val RESET_START_RIGHT = 24
        fun hasOperatorInput(payload: ByteArray): Boolean =
            (0..3).any { (payload[it].toInt() and 255) != 128 } ||
                (4..6).any { payload[it].toInt() != 0 }
    }

    @Synchronized fun request(value: Int, now: Long) {
        require(value in 1..31)
        nonce = nonce xor 0x20
        slot = value
        beginRelease()
    }

    @Synchronized fun stop() {
        slot = 0
        beginRelease()
    }

    private fun beginRelease() {
        releasing = true
        releaseStarted = -1L
        releaseFrames = 0
    }

    @Synchronized fun onTransmitted(payload: ByteArray, now: Long) {
        // Count successful USB writes; a prepared frame may fail to send.
        // A previous tap must not acknowledge the current release.
        if (releasing && (payload[6].toInt() and 255) == (0xC0 or nonce) &&
            (0..3).all { (payload[it].toInt() and 255) == 128 } &&
            payload[4].toInt() == 0 && payload[5].toInt() == 0) {
            if (releaseStarted < 0L) releaseStarted = now
            releaseFrames++
        }
    }

    @Synchronized fun fill(out: ByteArray, now: Long, operatorInput: Boolean = false): Boolean {
        if (releasing) {
            if (releaseStarted >= 0L && now - releaseStarted >= 120L && releaseFrames >= 4)
                releasing = false
        }
        if (slot == 0 && !releasing) return false
        // The gateway retains the selected destination. Ordinary controls and
        // dedicated angle frames must pass through without cancel or button gaps.
        if (!releasing && operatorInput) return false
        out[0] = 128.toByte(); out[1] = 128.toByte()
        out[2] = 128.toByte(); out[3] = 128.toByte()
        out[4] = 0; out[5] = 0
        val activeSlot = if (releasing) 0 else slot
        val header = if (activeSlot < 16) 0xC0 else 0x80
        out[6] = (header or nonce or ((activeSlot and 15) shl 1)).toByte()
        return true
    }
}

/** Each ordinary turn preserves held controls; dedicated turns cannot starve. */
class NavigationRadioMux {
    private var dedicatedTurn = true
    private var navigationTurn = true
    fun fill(out: ByteArray, now: Long, angle: AnglePresetCommand, navigation: SavedNavigationCommand) {
        // Emergency stop must reach the receiver even on a dedicated turn.
        if ((out[6].toInt() and 1) != 0) return
        if (dedicatedTurn) {
            if (navigation.releasePending) {
                navigation.fill(out, now)
                navigationTurn = true
            } else if (navigationTurn && navigation.transmitting) {
                navigation.fill(out, now)
                navigationTurn = false
            } else {
                if (!angle.fill(out, now)) navigation.fill(out, now)
                navigationTurn = true
            }
        }
        dedicatedTurn = !dedicatedTurn
    }
}

/** Shorten an idle deadline without sending faster than the UART budget. */
object RadioTxTiming {
    fun deadline(scheduled: Long, lastStart: Long, minimumInterval: Long, urgent: Boolean): Long =
        if (urgent) minOf(scheduled, lastStart + minimumInterval) else scheduled
}

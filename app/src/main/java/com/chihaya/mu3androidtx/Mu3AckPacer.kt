package com.chihaya.mu3androidtx

/**
 * MU-3 は @DT を内部バッファに溜め、無線の速さ（UARTよりずっと遅い）で順に送る。
 * 定周期で書き込むと、周期が無線の送信時間より短いとき・キャリアセンスで送信が待たされた
 * ときに、そのぶんだけフレームがMU-3内に溜まり、操作が届くまでの遅延が伸び続ける。
 *
 * MU-3 は1パケットにつき1回 `*DT=xx`（拒否なら `*IR=xx`）を返すので、それを待ってから
 * 次を書く。MU-3 内の待ち行列は常に高々1フレームになり、次のフレームは応答が来た瞬間の
 * 最新入力で作られる。
 *
 * 応答が欠けてもリンクを止めないよう、応答待ちは実測の応答時間から決める上限で打ち切る。
 * 応答を一度も返さない設定のモジュールでは、連続タイムアウトで待つのをやめ、従来の
 * 定周期送信に戻る。
 *
 * 送信スレッド（または停止時のニュートラル連送）だけが呼ぶ。統計値は表示用に volatile。
 */
class Mu3AckPacer(
    private val minWaitNs: Long = 25_000_000L,
    private val maxWaitNs: Long = 80_000_000L,
    private val noAckTimeoutsToGiveUp: Int = 8,
) {
    private var outstanding = 0
    private var sentAtNs = 0L
    private var ewmaAckNs = -1L
    private var consecutiveTimeouts = 0
    private val line = ByteArray(16)
    private var lineLen = 0

    @Volatile var acks = 0L
        private set
    @Volatile var rejects = 0L
        private set
    @Volatile var timeouts = 0L
        private set
    @Volatile var lastAckMs = -1.0
        private set

    /** 応答を一度も見ないまま連続でタイムアウトした＝応答を返さないモジュール。 */
    val pacingActive: Boolean
        get() = acks + rejects > 0 || consecutiveTimeouts < noAckTimeoutsToGiveUp

    @Synchronized fun reset() {
        outstanding = 0
        sentAtNs = 0L
        ewmaAckNs = -1L
        consecutiveTimeouts = 0
        lineLen = 0
    }

    @Synchronized fun onSent(nowNs: Long) {
        outstanding++
        sentAtNs = nowNs
    }

    /** 次の @DT を書いてよいか。 */
    @Synchronized fun ready(nowNs: Long): Boolean {
        if (outstanding == 0 || !pacingActive) return true
        if (nowNs - sentAtNs >= waitLimitNs()) {
            // 応答が失われた。待ち続けると操作が止まるので打ち切って次を送る
            outstanding = 0
            consecutiveTimeouts++
            timeouts++
            return true
        }
        return false
    }

    /** 応答待ちを打ち切る時刻。ready() が false の間だけ意味を持つ。 */
    @Synchronized fun waitDeadlineNs(): Long = sentAtNs + waitLimitNs()

    private fun waitLimitNs(): Long {
        if (ewmaAckNs < 0L) return maxWaitNs
        return (ewmaAckNs * 2 + 10_000_000L).coerceIn(minWaitNs, maxWaitNs)
    }

    /** MU-3 から読んだバイトを渡す。応答で送信枠が空いたら true。 */
    @Synchronized fun onBytes(buf: ByteArray, count: Int, nowNs: Long): Boolean {
        var released = false
        for (i in 0 until count) {
            val b = buf[i]
            if (b == '*'.code.toByte()) {
                lineLen = 0
            }
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                if (lineLen >= 4 && line[0] == '*'.code.toByte() && line[3] == '='.code.toByte()) {
                    val c1 = line[1].toInt().toChar()
                    val c2 = line[2].toInt().toChar()
                    if (c1 == 'D' && c2 == 'T') {
                        acks++
                        released = onResponse(nowNs, sample = true) || released
                    } else if (c1 == 'I' && c2 == 'R') {
                        // 拒否されたフレームはMU-3に残らないので、送信枠も空く
                        rejects++
                        released = onResponse(nowNs, sample = false) || released
                    }
                }
                lineLen = 0
                continue
            }
            if (lineLen < line.size) line[lineLen++] = b
        }
        return released
    }

    private fun onResponse(nowNs: Long, sample: Boolean): Boolean {
        consecutiveTimeouts = 0
        if (outstanding == 0) return false
        outstanding--
        if (sample && outstanding == 0) {
            val latency = nowNs - sentAtNs
            if (latency in 0L..maxWaitNs) {
                ewmaAckNs = if (ewmaAckNs < 0L) latency else (ewmaAckNs * 7 + latency) / 8
                lastAckMs = latency / 1_000_000.0
            }
        }
        return outstanding == 0
    }
}

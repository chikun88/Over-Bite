package com.chihaya.mu3androidtx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 電池残量が [threshold]% 以下かどうかから、警告音を鳴らすタイミングを決める（Android に依存しない純粋なロジック）。
 *
 * - 監視を始めて最初に届いた値が [threshold] 以下なら、充電中でも必ず鳴らす（アプリ起動時など）。
 * - その後は、[threshold] より上から [threshold] 以下に下がった瞬間に鳴らす。
 * - 一度鳴ったら、[threshold] より上に戻るまで再び鳴らない（1% 減るたびに鳴り続けないため）。
 */
class LowBatteryTrigger(private val threshold: Int = 30) {
    private var lastPercent: Int? = null

    fun update(percent: Int): Boolean {
        val previous = lastPercent
        lastPercent = percent
        if (percent > threshold) return false
        return previous == null || previous > threshold
    }
}

/**
 * 電池残量が 30% 以下なら res/raw/low_battery_alarm.m4a（約 5 秒）を爆音で鳴らす。
 * MainActivity の起動時（監視開始時）にすでに 30% 以下なら必ず鳴らし、使用中は 30% 以下に下がった瞬間に鳴らす。
 *
 * 音だけを流し、画面・操作には一切触れない（View もダイアログも出さず、オーディオフォーカスも取らない）。
 * 再生中だけメディア音量を最大にし LoudnessEnhancer で +15dB 持ち上げ、終わったら元の音量に戻す。
 * MainActivity が生きている間（バックグラウンドでも）監視する。
 */
class LowBatteryAlarm(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val trigger = LowBatteryTrigger(THRESHOLD_PERCENT)
    private var registered = false
    private var player: MediaPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private var savedMusicVolume = -1
    private val stopRunnable = Runnable { stopPlayback() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            if (trigger.update(percent)) play()
        }
    }

    /** 監視を始める。ACTION_BATTERY_CHANGED は sticky なので、登録直後に現在値が 1 回届く。 */
    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (registered) {
            try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        stopPlayback()
    }

    private fun play() {
        if (player != null) return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(appContext, Uri.parse("android.resource://${appContext.packageName}/${R.raw.low_battery_alarm}"))
            mp.setVolume(1f, 1f)
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            mp.setOnPreparedListener {
                raiseMusicVolumeToMax()
                it.start()
                handler.postDelayed(stopRunnable, PLAY_MS)
            }
            try {
                loudness = LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain(LOUDNESS_GAIN_MB)
                    enabled = true
                }
            } catch (_: Exception) {
                loudness = null
            }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(stopRunnable)
        loudness?.let { try { it.release() } catch (_: Exception) {} }
        loudness = null
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        restoreMusicVolume()
    }

    private fun raiseMusicVolumeToMax() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (savedMusicVolume < 0) savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (_: Exception) {
            // おやすみモードの設定などで変更できない端末もあるので無視する。
        }
    }

    private fun restoreMusicVolume() {
        if (savedMusicVolume < 0) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        } catch (_: Exception) {
        }
        savedMusicVolume = -1
    }

    private companion object {
        const val THRESHOLD_PERCENT = 30
        /** 再生時間の上限。音声ファイル自体も 5 秒。 */
        const val PLAY_MS = 5000L
        /** LoudnessEnhancer の増幅量（ミリベル）。1500 = +15dB。 */
        const val LOUDNESS_GAIN_MB = 1500
    }
}

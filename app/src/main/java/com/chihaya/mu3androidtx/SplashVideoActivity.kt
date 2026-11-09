package com.chihaya.mu3androidtx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 起動演出:
 *  1. res/raw/splash_video.mp4 を画面いっぱい（中央クロップ）で再生する。**スキップ不可**。
 *     音は必ず爆音で鳴らす: 再生中だけメディア音量を最大にし（マナー/サイレント中も）、
 *     LoudnessEnhancer でさらに +15dB 持ち上げる。画面を離れたら元の音量に戻す。
 *  2. 再生が終わったら、アイコンに合わせたドット絵の「OVER BITE」ロゴ（[OverbiteLogoView]）を
 *     ピクセルディゾルブで重ねて一瞬表示する。**タップでスキップ可**。
 *  3. MainActivity へフェードで遷移する。
 *
 * 起動の段差をなくすため、黒（Android 12+ のシステムスプラッシュも黒・アイコンなし）から
 * 動画の 1 フレーム目（res/drawable-nodpi/splash_poster.webp）へフェードインし、
 * MediaPlayer の準備が終わって実際の映像が出た瞬間にポスターを外す。
 *
 * MainActivity は 6000 行超で USB 接続処理も持つため、起動演出は別 Activity に分離して
 * 既存の USB_DEVICE_ATTACHED 起動経路には一切影響しないようにしている。
 */
class SplashVideoActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var root: FrameLayout
    private lateinit var textureView: TextureView
    private var posterView: ImageView? = null
    private lateinit var logoView: OverbiteLogoView

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var resumed = false
    private var finishedToMain = false
    private var resumePositionMs = 0

    /** 動画が終わってロゴ表示に入ったか。ロゴ中は動画を再生し直さない。 */
    private var logoPhase = false
    private var logoAnimator: ValueAnimator? = null
    private var hapticDone = false
    /** showLogo が二重に走らないためのフラグ（動画のエラーと完了が両方来る端末がある）。 */
    private var logoShown = false
    private val goToMainRunnable = Runnable { goToMain() }
    private var loudness: LoudnessEnhancer? = null
    /** 再生前のメディア音量。再生中だけ最大にし、画面を離れたら元に戻す。 */
    private var savedMusicVolume = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ランチャーから再度タップされた場合、既存タスクが前面に来るだけなので動画は流さない。
        if (!isTaskRoot && intent?.action == Intent.ACTION_MAIN &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        ) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        resumePositionMs = savedInstanceState?.getInt(KEY_POSITION, 0) ?: 0
        logoPhase = savedInstanceState?.getBoolean(KEY_LOGO_PHASE, false) ?: false
        val freshStart = savedInstanceState == null

        val match = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        textureView = TextureView(this).apply { surfaceTextureListener = this@SplashVideoActivity }
        logoView = OverbiteLogoView(this, appVersionLabel()).apply {
            visibility = View.GONE
            isHapticFeedbackEnabled = true
            // ロゴ表示中だけタップでスキップできる（動画中はこの View が GONE なので触っても何も起きない）。
            setOnClickListener { if (logoPhase) goToMain() }
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textureView, match)
            // 動画の 1 フレーム目。準備待ちの間に黒画面を見せないためのもので、途中再開時は位置がずれるので出さない。
            if (resumePositionMs == 0 && !logoPhase) {
                posterView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.splash_poster)
                }
                addView(posterView, match)
            }
            addView(logoView, match)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCenterCrop() }
        setContentView(root)
        hideSystemBars()

        if (logoPhase) {
            showLogo(animateIn = false)
        } else if (freshStart) {
            // 黒（システムスプラッシュ）から映像へフェードイン。
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (finishedToMain) return
        if (logoPhase) {
            logoAnimator?.resume()
        } else {
            raiseMusicVolumeToMax()
            if (prepared) player?.start()
        }
    }

    override fun onPause() {
        resumed = false
        player?.let { if (it.isPlaying) it.pause() }
        logoAnimator?.pause()
        restoreMusicVolume()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // MainActivity が画面を覆ってから閉じる（goToMain 参照）。
        if (finishedToMain && !isFinishing) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, player?.currentPosition ?: resumePositionMs)
        outState.putBoolean(KEY_LOGO_PHASE, logoPhase)
    }

    override fun onDestroy() {
        if (::logoView.isInitialized) logoView.removeCallbacks(goToMainRunnable)
        finishedToMain = true
        logoAnimator?.removeAllListeners()
        logoAnimator?.cancel()
        logoAnimator = null
        releasePlayer()
        super.onDestroy()
    }

    // --- TextureView.SurfaceTextureListener ---

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        if (!logoPhase) startPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyCenterCrop()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        player?.let { resumePositionMs = it.currentPosition }
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // 実際の映像フレームが描かれたらポスターを外す（ポスター＝1 フレーム目なので見た目は変わらない）。
        hidePoster()
    }

    /**
     * onSurfaceTextureUpdated は TextureView の描画中（dispatchDraw の途中）に呼ばれるため、
     * ここで removeView すると ViewGroup.dispatchDraw が NPE で落ちる。
     * その場では非表示にするだけにして、取り外しは次のフレームに回す。
     */
    private fun hidePoster() {
        val v = posterView ?: return
        posterView = null
        v.visibility = View.GONE
        root.post { root.removeView(v) }
    }

    // --- 再生 ---

    private fun startPlayer() {
        val s = surface ?: return
        releasePlayerOnly()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setDataSource(this, Uri.parse("android.resource://$packageName/${R.raw.splash_video}"))
            mp.setSurface(s)
            mp.setVolume(1f, 1f)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, _, _ -> applyCenterCrop() }
            attachLoudnessBoost(mp)
            mp.setOnPreparedListener {
                prepared = true
                applyCenterCrop()
                if (resumePositionMs > 0) it.seekTo(resumePositionMs)
                if (resumed) {
                    raiseMusicVolumeToMax()
                    it.start()
                }
            }
            mp.setOnCompletionListener { showLogo(animateIn = true) }
            mp.setOnErrorListener { _, _, _ ->
                // 動画が再生できない端末でもロゴは見せてから進む。
                showLogo(animateIn = true)
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            showLogo(animateIn = true)
        }
    }

    /** 動画の縦横比を保ったまま画面全体を覆うように拡大し、はみ出た部分を中央でクロップする。 */
    private fun applyCenterCrop() {
        val mp = player ?: return
        if (!prepared) return
        val vw = mp.videoWidth.toFloat()
        val vh = mp.videoHeight.toFloat()
        val tw = textureView.width.toFloat()
        val th = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || tw <= 0f || th <= 0f) return

        // TextureView は既定で動画をビューいっぱいに引き伸ばすので、比率を補正してから拡大する。
        val scale = maxOf(tw / vw, th / vh)
        val sx = vw * scale / tw
        val sy = vh * scale / th
        val m = Matrix()
        m.setScale(sx, sy, tw / 2f, th / 2f)
        textureView.setTransform(m)
    }

    // --- ロゴ ---

    /**
     * ドット絵ロゴを表示してアニメーションを流し、終わったら MainActivity へ進む。
     * ロゴの背景はピクセル単位で順に埋まる（OverbiteLogoView 側）ので、[animateIn] が true のときは
     * 動画の最後のフレームがブロック状に崩れてロゴへ切り替わる。false（画面回転などからの復帰）は
     * 背景が最初から埋まった状態で始める。
     */
    private fun showLogo(animateIn: Boolean) {
        if (finishedToMain || logoShown) return
        logoShown = true
        logoPhase = true
        hidePoster()
        root.animate().cancel()
        root.alpha = 1f
        restoreMusicVolume()
        logoView.skipDissolve = !animateIn
        logoView.progress = 0f
        logoView.visibility = View.VISIBLE
        // 動画（最後のフレームで止まっている）はディゾルブの下に見せたいので、解放は遷移時に行う。
        startLogoAnimation()
    }

    private fun startLogoAnimation() {
        if (finishedToMain) return
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOGO_ANIM_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                logoView.progress = p
                // ロゴが揃ってグリッチが走る瞬間に振動させる。
                if (!hapticDone && p >= HAPTIC_AT) {
                    hapticDone = true
                    logoView.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                        else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // ロゴを見せきってから少しだけ止めて遷移する。
                    logoView.postDelayed(goToMainRunnable, LOGO_HOLD_MS)
                }
            })
        }
        logoAnimator = anim
        anim.start()
        if (!resumed) anim.pause()
    }

    private fun goToMain() {
        if (finishedToMain || isFinishing) return
        finishedToMain = true
        logoView.removeCallbacks(goToMainRunnable)
        logoAnimator?.removeAllListeners()
        logoAnimator?.cancel()
        releasePlayer()
        restoreMusicVolume()
        startActivity(Intent(this, MainActivity::class.java))
        // ここで finish() するとタスクの根が消えるため、Android 12+ では MainActivity に灰色の
        // システムスプラッシュが挟まる。ロゴを出したまま MainActivity を重ね、onStop で閉じる。
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // --- 補助 ---

    /** 端末のメディア音量を最大にする（元の値は restoreMusicVolume で戻す）。 */
    private fun raiseMusicVolumeToMax() {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (savedMusicVolume < 0) savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (_: Exception) {
            // おやすみモードの設定などで変更できない端末もあるので無視する。
        }
    }

    private fun restoreMusicVolume() {
        if (savedMusicVolume < 0) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
        try {
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        } catch (_: Exception) {
        }
        savedMusicVolume = -1
    }

    /** 最大音量のさらに上まで音を持ち上げる（+15dB）。非対応端末では何もしない。 */
    private fun attachLoudnessBoost(mp: MediaPlayer) {
        try {
            loudness = LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain(LOUDNESS_GAIN_MB)
                enabled = true
            }
        } catch (_: Exception) {
            loudness = null
        }
    }

    /** MainActivity のヘッダーと同じ「v1.1 (2)」形式のバージョン表記。 */
    private fun appVersionLabel(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "v${info.versionName} ($code)"
        } catch (_: Exception) {
            "v?"
        }
    }

    private fun releasePlayerOnly() {
        loudness?.let { try { it.release() } catch (_: Exception) {} }
        loudness = null
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        prepared = false
    }

    private fun releasePlayer() {
        releasePlayerOnly()
        surface?.release()
        surface = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private companion object {
        const val KEY_POSITION = "splash_position_ms"
        const val KEY_LOGO_PHASE = "splash_logo_phase"
        const val FADE_IN_MS = 220L
        /** ロゴのアニメーション本体の長さ（ディゾルブ込み）。 */
        const val LOGO_ANIM_MS = 1700L
        /** アニメーション後に静止して見せる時間。 */
        const val LOGO_HOLD_MS = 400L
        /** OverbiteLogoView でロゴが揃ってグリッチが走る位置（progress）。 */
        const val HAPTIC_AT = OverbiteLogoView.IMPACT_AT
        /** LoudnessEnhancer の増幅量（ミリベル）。1500 = +15dB。 */
        const val LOUDNESS_GAIN_MB = 1500
    }
}

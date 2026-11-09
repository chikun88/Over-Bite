package com.chihaya.mu3androidtx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 起動動画のあとに一瞬出す「OVER BITE」の文字ロゴ。
 *
 * [progress] (0→1) で次の順にアニメーションする:
 *  - 0.00–0.45 文字が広い字間から詰まりながらフェードイン
 *  - 0.30–0.65 下線のアクセントバーが中央から伸びる
 *  - 0.45–0.85 光の帯が文字の上を左から右へ走る
 *  - 0.50–0.80 サブタイトル（MU-3 LIVE CONTROL / バージョン）が出る
 * 色は MainActivity のダークテーマのアクセント（#A9B7FF / #8AB4F8 / #64D8D0 / #3559D6）に合わせている。
 */
// コードからのみ生成し、アニメーションは約1.5秒だけなので描画中のシェーダー生成は許容する。
@SuppressLint("ViewConstructor", "DrawAllocation")
class OverbiteLogoView(context: Context, private val versionLabel: String) : View(context) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint()
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSkewX = -0.18f
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = titlePaint.typeface
        textAlign = Paint.Align.CENTER
        textSkewX = titlePaint.textSkewX
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = ACCENT_LAVENDER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    private var titleSize = 0f
    private var startSpacing = MAX_START_SPACING

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bgPaint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.62f,
            intArrayOf(Color.parseColor("#1A2140"), Color.parseColor("#0B0E19"), Color.parseColor("#030407")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        titleSize = min(w * 0.125f, h * 0.30f)
        titlePaint.textSize = titleSize
        titlePaint.letterSpacing = END_SPACING
        // 最終形が画面幅の 80% を超える端末（縦長/狭い画面）では文字を縮める。
        val endWidth = titlePaint.measureText(TITLE)
        if (endWidth > w * 0.8f) {
            titleSize *= w * 0.8f / endWidth
            titlePaint.textSize = titleSize
        }
        shinePaint.textSize = titleSize
        // 開始時の広い字間でも画面からはみ出さない範囲に抑える（letterSpacing は 1 文字ごとに em 単位で足される）。
        val fitted = titlePaint.measureText(TITLE)
        startSpacing = (END_SPACING + (w * 0.96f - fitted) / (TITLE.length * titleSize))
            .coerceIn(END_SPACING, MAX_START_SPACING)
        subPaint.textSize = max(titleSize * 0.15f, 10f * density)
        subPaint.letterSpacing = 0.32f
        hintPaint.textSize = 10f * density
        hintPaint.letterSpacing = 0.28f
        titlePaint.setShadowLayer(titleSize * 0.22f, 0f, 0f, Color.argb(150, 0x35, 0x59, 0xD6))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (titleSize <= 0f) return

        val p = progress
        val cx = w / 2f
        // 文字の視覚的な中心が画面中央より少し上に来るように置く（下にバーとサブタイトルが続くため）。
        val baseline = h / 2f + titleSize * 0.18f

        // --- タイトル: 字間が詰まりながらフェードイン ---
        val reveal = easeOutCubic(segment(p, 0f, 0.45f))
        titlePaint.letterSpacing = lerp(startSpacing, END_SPACING, reveal)
        shinePaint.letterSpacing = titlePaint.letterSpacing
        val textWidth = titlePaint.measureText(TITLE)
        val left = cx - textWidth / 2f
        val right = cx + textWidth / 2f
        titlePaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(Color.WHITE, ACCENT_LAVENDER, ACCENT_BLUE, ACCENT_TEAL),
            floatArrayOf(0f, 0.38f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        titlePaint.alpha = (255 * easeOutCubic(segment(p, 0f, 0.3f))).toInt()
        canvas.drawText(TITLE, cx, baseline, titlePaint)

        // --- 光の帯が左から右へ走る ---
        val shine = segment(p, 0.45f, 0.85f)
        if (shine > 0f && shine < 1f) {
            val band = textWidth * 0.18f
            val x = lerp(left - band, right + band, easeInOutSine(shine))
            shinePaint.shader = LinearGradient(
                x - band, 0f, x + band, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawText(TITLE, cx, baseline, shinePaint)
        }

        // --- 下線のアクセントバー ---
        val bar = easeOutCubic(segment(p, 0.3f, 0.65f))
        if (bar > 0f) {
            val barHalf = textWidth * 0.5f * bar
            val barTop = baseline + titleSize * 0.2f
            val barH = max(titleSize * 0.045f, 2f * density)
            barPaint.shader = LinearGradient(
                cx - barHalf, 0f, cx + barHalf, 0f,
                intArrayOf(ACCENT_INDIGO, ACCENT_BLUE, ACCENT_TEAL),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(cx - barHalf, barTop, cx + barHalf, barTop + barH, barH, barH, barPaint)
        }

        // --- サブタイトル ---
        val sub = easeOutCubic(segment(p, 0.5f, 0.8f))
        if (sub > 0f) {
            subPaint.alpha = (255 * sub).toInt()
            val subY = baseline + titleSize * 0.2f + titleSize * 0.42f + (1f - sub) * titleSize * 0.12f
            canvas.drawText("MU-3 LIVE CONTROL  ·  $versionLabel", cx, subY, subPaint)
        }

        // --- スキップ案内（ロゴだけスキップ可能） ---
        val hint = segment(p, 0.4f, 0.7f)
        if (hint > 0f) {
            hintPaint.alpha = (110 * hint).toInt()
            canvas.drawText("TAP TO SKIP", cx, h - 22f * density, hintPaint)
        }
    }

    private companion object {
        const val TITLE = "OVER BITE"
        const val END_SPACING = 0.06f
        const val MAX_START_SPACING = 0.55f
        val ACCENT_LAVENDER = Color.parseColor("#A9B7FF")
        val ACCENT_BLUE = Color.parseColor("#8AB4F8")
        val ACCENT_TEAL = Color.parseColor("#64D8D0")
        val ACCENT_INDIGO = Color.parseColor("#3559D6")

        fun segment(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun easeOutCubic(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }
        fun easeInOutSine(t: Float) = (0.5f - 0.5f * kotlin.math.cos(Math.PI * t)).toFloat()
    }
}

package com.chihaya.mu3androidtx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 起動動画のあとに一瞬出す、アイコン（ドット絵）に合わせたピクセルフォントの「OVER BITE」ロゴ。
 * 色はアイコンのパレット（紺 #0C1015 / クリーム #FCDCAF / オレンジ #E39141 / 茶 #A15119 / こげ茶 #491C11）。
 *
 * [progress] (0→1) の流れ:
 *  - 0.00–0.16 動画の最後のフレームがブロック単位でランダムに崩れ、紺の背景に置き換わる
 *  - 0.10–0.40 ロゴのドットが 1 つずつ白く光って現れ、色が乗る
 *  - [IMPACT_AT]–0.56 揃った瞬間にグリッチ（RGB ずれ・行ずれ・揺れ・白フラッシュ）
 *  - 0.52–0.66 下のドットバーが中央から伸びる
 *  - 0.58–0.84 光の帯が斜めに走る
 *  - 0.60–0.84 サブタイトルがタイプライター風に打ち出される
 * 全体に CRT 風の走査線と周辺減光を重ねる。
 */
// コードからのみ生成する View。描画は約 1.7 秒だけなので毎フレームの計算は許容する。
@SuppressLint("ViewConstructor", "DrawAllocation")
class OverbiteLogoView(context: Context, versionLabel: String) : View(context) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** true なら背景のディゾルブを省き、最初から背景が埋まった状態で始める（状態復元時など）。 */
    var skipDissolve = false

    private val density = resources.displayMetrics.density
    private val subtitle = "MU-3 LIVE CONTROL · ${versionLabel.uppercase()}"

    private val fill = Paint() // ドットはアンチエイリアスなしでくっきり描く
    private val bgPaint = Paint()
    private val vignettePaint = Paint()

    /** ロゴのドット 1 つ分（列・行はロゴ内のマス目、row はグリフ内の行で色を決める）。 */
    private class Dot(val col: Int, val row: Int, val glyphRow: Int, val delay: Float)

    private val titleDots: List<Dot>
    private val titleCols: Int

    private var cell = 0f
    private var originX = 0f
    private var originY = 0f
    private var subCell = 0f
    private var hintCell = 0f
    private var blockSize = 0f
    private var blockCols = 0
    private var blockRows = 0
    private var blockOrder = FloatArray(0)

    init {
        val rnd = Random(0x0B17E)
        val dots = ArrayList<Dot>()
        TITLE.forEachIndexed { i, ch ->
            val glyph = glyphOf(ch)
            for (r in 0 until GLYPH_H) for (c in 0 until GLYPH_W) {
                if (glyph[r][c] == '#') {
                    // 左のドットほど少し早く出るようにして、左から組み上がる流れを作る。
                    val bias = i / TITLE.length.toFloat() * 0.45f
                    dots += Dot(i * ADVANCE + c, r, r, (bias + rnd.nextFloat() * 0.55f).coerceIn(0f, 1f))
                }
            }
        }
        titleDots = dots
        titleCols = TITLE.length * ADVANCE - 1
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // ロゴの幅を画面幅の約 78% に。ドットは整数ピクセルにそろえてにじませない。
        cell = floor(min(w * 0.78f / titleCols, h * 0.26f / GLYPH_H)).coerceAtLeast(2f)
        originX = floor((w - titleCols * cell) / 2f)
        originY = floor(h * 0.44f - GLYPH_H * cell / 2f)
        subCell = floor(max(cell / 4.2f, 1.5f * density)).coerceAtLeast(1f)
        while (subCell > 1f && (subtitle.length * ADVANCE - 1) * subCell > w * 0.9f) subCell -= 1f
        hintCell = floor(max(subCell * 0.7f, 1.2f * density)).coerceAtLeast(1f)

        bgPaint.shader = null
        bgPaint.color = BG
        vignettePaint.shader = RadialGradient(
            w / 2f, h * 0.44f, max(w, h) * 0.7f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(170, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        blockSize = max(cell * 1.5f, w / 36f).roundToInt().toFloat()
        blockCols = (w / blockSize).toInt() + 1
        blockRows = (h / blockSize).toInt() + 1
        val rnd = Random(0x5EED)
        blockOrder = FloatArray(blockCols * blockRows) { rnd.nextFloat() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val p = progress

        // --- 1. 背景: 動画の最後のフレームをブロック単位で置き換える ---
        val dissolve = if (skipDissolve) 1f else segment(p, 0f, 0.16f)
        if (dissolve >= 1f) {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        } else if (dissolve > 0f) {
            for (by in 0 until blockRows) for (bx in 0 until blockCols) {
                if (blockOrder[by * blockCols + bx] < dissolve) {
                    val x = bx * blockSize
                    val y = by * blockSize
                    canvas.drawRect(x, y, x + blockSize, y + blockSize, bgPaint)
                }
            }
        }

        // --- 2. タイトル ---
        val glitch = p >= IMPACT_AT && p < GLITCH_END
        val frameSeed = (p * 400).toInt()
        val gr = Random(frameSeed)
        val shakeX = if (glitch) (gr.nextInt(-2, 3) * cell * 0.25f) else 0f
        val shakeY = if (glitch) (gr.nextInt(-1, 2) * cell * 0.25f) else 0f
        // グリッチ中は一部の行だけ横にずらす（走査線が乱れたような見え方）。
        // 前半だけ強く、後半は RGB ずれだけ残して収束させる。
        val hardGlitch = glitch && p < IMPACT_AT + (GLITCH_END - IMPACT_AT) * 0.5f
        val rowShift = IntArray(GLYPH_H) { if (hardGlitch && gr.nextFloat() < 0.25f) (if (gr.nextBoolean()) 1 else -1) else 0 }

        val materialize = segment(p, 0.10f, 0.40f)
        val sweep = segment(p, 0.58f, 0.84f)
        val sweepCol = -10f + (titleCols + 20f) * easeInOut(sweep)

        if (glitch) {
            val fade = 1f - segment(p, IMPACT_AT, GLITCH_END)
            val split = cell * (0.2f + gr.nextFloat() * 0.45f) * (0.35f + 0.65f * fade)
            drawTitle(canvas, materialize, shakeX - split, shakeY, rowShift, solid = SPLIT_RED, sweepCol = null)
            drawTitle(canvas, materialize, shakeX + split, shakeY, rowShift, solid = SPLIT_CYAN, sweepCol = null)
        }
        // ドットのにじみ（ブロック状のグロー）→ 影 → 本体の順に描く。
        drawTitle(canvas, materialize, shakeX, shakeY, rowShift, solid = GLOW, sweepCol = null, inflate = cell * 0.35f)
        drawTitle(canvas, materialize, shakeX + cell * 0.5f, shakeY + cell * 0.5f, rowShift, solid = SHADOW, sweepCol = null)
        drawTitle(canvas, materialize, shakeX, shakeY, rowShift, solid = null,
            sweepCol = if (sweep > 0f && sweep < 1f) sweepCol else null)

        // --- 3. ドットバー ---
        val bar = easeOut(segment(p, 0.52f, 0.66f))
        if (bar > 0f) {
            val barCells = (titleCols * bar).roundToInt()
            val startCol = (titleCols - barCells) / 2
            val y = originY + (GLYPH_H + 1.5f) * cell
            val bh = max(cell * 0.5f, 2f)
            for (c in 0 until barCells) {
                val x = originX + (startCol + c) * cell
                fill.color = SHADOW
                canvas.drawRect(x + cell * 0.5f, y + bh * 0.6f, x + cell * 1.5f - 1f, y + bh * 1.6f, fill)
                fill.color = if ((c / 2) % 2 == 0) ORANGE else BROWN
                canvas.drawRect(x, y, x + cell - 1f, y + bh, fill)
            }
        }

        // --- 4. サブタイトル（タイプライター＋点滅カーソル） ---
        val typing = segment(p, 0.60f, 0.84f)
        if (typing > 0f) {
            val shown = (subtitle.length * typing).toInt().coerceIn(0, subtitle.length)
            val subW = (subtitle.length * ADVANCE - 1) * subCell
            val sx = floor((w - subW) / 2f)
            val sy = floor(originY + (GLYPH_H + 4f) * cell)
            drawText(canvas, subtitle.substring(0, shown), sx, sy, subCell, CREAM_DIM)
            val cursorOn = typing < 1f || (frameSeed / 24) % 2 == 0
            if (cursorOn) {
                fill.color = ORANGE
                val cx = sx + shown * ADVANCE * subCell
                canvas.drawRect(cx, sy, cx + GLYPH_W * subCell, sy + GLYPH_H * subCell, fill)
            }
        }

        // --- 5. スキップ案内（ロゴだけスキップ可能） ---
        if (p > 0.5f && (frameSeed / 30) % 2 == 0) {
            val hint = "TAP TO SKIP"
            val hw = (hint.length * ADVANCE - 1) * hintCell
            drawText(canvas, hint, floor((w - hw) / 2f), floor(h - 18f * density - GLYPH_H * hintCell), hintCell, HINT)
        }

        // --- 6. 白フラッシュ・走査線・周辺減光 ---
        if (p >= IMPACT_AT && p < IMPACT_AT + 0.08f) {
            val f = 1f - segment(p, IMPACT_AT, IMPACT_AT + 0.08f)
            fill.color = Color.argb((90 * f).toInt(), 255, 236, 210)
            canvas.drawRect(0f, 0f, w, h, fill)
        }
        val crt = if (skipDissolve) 1f else dissolve
        if (crt > 0f) {
            fill.color = Color.argb((46 * crt).toInt(), 0, 0, 0)
            val step = max(3f, (2f * density).roundToInt().toFloat())
            var y = 0f
            while (y < h) {
                canvas.drawRect(0f, y, w, y + step / 2f, fill)
                y += step
            }
            vignettePaint.alpha = (255 * crt).toInt()
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
    }

    /**
     * タイトルのドットを描く。[solid] が null ならアイコンのパレットで行ごとに塗り分け、
     * 出現直後のドットは白く光らせる。[sweepCol] の近くのドットは光の帯で明るくする。
     */
    private fun drawTitle(
        canvas: Canvas, materialize: Float, dx: Float, dy: Float, rowShift: IntArray,
        solid: Int?, sweepCol: Float?, inflate: Float = 0f
    ) {
        for (d in titleDots) {
            val age = materialize - d.delay * 0.85f // 0 未満ならまだ出ていない
            if (age < 0f) continue
            val x = originX + (d.col + rowShift[d.glyphRow]) * cell + dx
            val y = originY + d.row * cell + dy
            // 出現の瞬間は少し大きく、すぐ 1 マスに収まる。
            val pop = if (age < 0.08f) (1f - age / 0.08f) * cell * 0.25f else 0f
            val color = when {
                solid != null -> solid
                age < 0.06f -> Color.WHITE
                sweepCol != null && abs(d.col - d.row * 0.6f - sweepCol) < 2.2f -> Color.WHITE
                else -> ROW_COLORS[d.glyphRow]
            }
            fill.color = color
            val g = inflate + pop
            canvas.drawRect(x - g, y - g, x + cell - 1f + g, y + cell - 1f + g, fill)
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        fill.color = color
        text.forEachIndexed { i, ch ->
            val glyph = glyphOf(ch)
            val gx = x + i * ADVANCE * size
            for (r in 0 until GLYPH_H) for (c in 0 until GLYPH_W) {
                if (glyph[r][c] == '#') {
                    val px = gx + c * size
                    val py = y + r * size
                    canvas.drawRect(px, py, px + size, py + size, fill)
                }
            }
        }
    }

    companion object {
        /** ロゴが揃ってグリッチが走る位置。SplashVideoActivity はここで振動させる。 */
        const val IMPACT_AT = 0.42f
        private const val GLITCH_END = 0.56f

        private const val TITLE = "OVER BITE"
        private const val GLYPH_W = 5
        private const val GLYPH_H = 7
        private const val ADVANCE = GLYPH_W + 1

        // アイコンのパレット
        private val BG = Color.parseColor("#0C1015")
        private val CREAM = Color.parseColor("#FCDCAF")
        private val PEACH = Color.parseColor("#E2B17C")
        private val ORANGE = Color.parseColor("#E39141")
        private val BROWN = Color.parseColor("#A15119")
        private val SHADOW = Color.parseColor("#491C11")
        private val GLOW = Color.argb(40, 0xE3, 0x91, 0x41)
        private val CREAM_DIM = Color.argb(220, 0xFC, 0xDC, 0xAF)
        private val HINT = Color.argb(120, 0xFC, 0xDC, 0xAF)
        private val SPLIT_RED = Color.argb(170, 0xFF, 0x3B, 0x5C)
        private val SPLIT_CYAN = Color.argb(170, 0x3B, 0xE8, 0xFF)
        /** グリフの行（上→下）ごとの色。上がクリーム、下へいくほどオレンジ→茶。 */
        private val ROW_COLORS = intArrayOf(CREAM, CREAM, PEACH, ORANGE, ORANGE, BROWN, BROWN)

        private fun segment(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
        private fun easeOut(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }
        private fun easeInOut(t: Float) = (0.5f - 0.5f * kotlin.math.cos(Math.PI * t)).toFloat()

        private val BLANK = Array(GLYPH_H) { "....." }
        private fun glyphOf(ch: Char): Array<String> = FONT[ch.uppercaseChar()] ?: BLANK

        /** 5×7 のピクセルフォント（ロゴとサブタイトルで使う文字）。 */
        private val FONT: Map<Char, Array<String>> = mapOf(
            'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
            'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
            'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
            'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
            'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
            'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
            'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
            'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
            'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
            'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
            'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
            'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
            'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
            'N' to arrayOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
            'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
            'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
            'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
            'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
            'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
            'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
            'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
            'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
            'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
            'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
            'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
            'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
            '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
            '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
            '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
            '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
            '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
            '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
            '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
            '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
            '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
            '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
            '-' to arrayOf(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
            '.' to arrayOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
            '(' to arrayOf("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."),
            ')' to arrayOf(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."),
            '·' to arrayOf(".....", ".....", ".....", "..#..", ".....", ".....", "....."),
            '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
            ' ' to BLANK,
        )
    }
}

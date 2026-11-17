package com.chihaya.mu3androidtx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Schematic normalized coordinates; actual positions are stored on Jetson. */
data class SavedNavigationPoint(
    val label: String, val jetsonName: String, val slot: Int,
    val x: Double, val y: Double,
)
const val SAVED_NAVIGATION_SIDE_SLOT_OFFSET = 8
// The annotated blue marks, aligned into rows and columns for landscape use.
val SAVED_NAVIGATION_POINTS = listOf(
    SavedNavigationPoint("自動装填", "A", 1, .07, .48),
    SavedNavigationPoint("手前バケツ", "BAKETU2", 2, .38, .13),
    SavedNavigationPoint("奥バケツ", "BAKETU3", 3, .74, .13),
    SavedNavigationPoint("手前正面", "旗上側", 4, .50, .13),
    SavedNavigationPoint("奥正面", "旗下側", 5, .62, .13),
    SavedNavigationPoint("退避位置", "退避位置", 6, .55, .76),
    SavedNavigationPoint("スタート\n位置", "装填位置", 7, .07, .26),
    SavedNavigationPoint("手前右", "机保護1", 17, .30, .48),
    SavedNavigationPoint("奥左", "机保護2", 18, .81, .48),
    SavedNavigationPoint("手前左", "バケツ後2", 19, .47, .48),
    SavedNavigationPoint("奥右", "バケツ後3", 20, .64, .48),
    SavedNavigationPoint("IDOU", "IDOU", 23, .16, .13),
    // Slot 21 was the never-recorded バケツ2B; the Jetson list now names it start mae.
    SavedNavigationPoint("スタート\n前", "start mae", 21, .07, .70),
)

/** Accessible native circular buttons, mirrored together with field selection. */
class SavedNavigationMapView(
    context: Context, private val points: List<SavedNavigationPoint>,
    private val palette: Palette, private val onSelect: (SavedNavigationPoint) -> Unit,
) : FrameLayout(context) {
    class Palette(val surface: Int, val fieldFill: Int, val outline: Int,
        val gridLine: Int, val divider: Int, val marker: Int, val markerSelected: Int,
        val textPrimary: Int, val textOnMarker: Int, val textSecondary: Int)
    var rightField: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }
    var selected: SavedNavigationPoint? = null
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val buttons = points.map { point ->
        Button(context).apply {
            text = when (point.label) {
                "手前バケツ" -> "手前\nバケツ"
                "奥バケツ" -> "奥\nバケツ"
                "手前正面" -> "手前\n正面"
                "奥正面" -> "奥\n正面"
                "手前右" -> "手前\n右"
                "手前左" -> "手前\n左"
                "自動装填" -> "自動\n装填"
                "退避位置" -> "退避\n位置"
                else -> point.label
            }
            isAllCaps = false; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false
            maxLines = 2; setLineSpacing(0f, 1f)
            setTextColor(palette.textPrimary); setPadding(0,0,0,0)
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            contentDescription = point.label.replace("\n", "") + "へ移動"
            setOnClickListener { selected = point; updateMarkers(); onSelect(point) }
            addView(this)
        }
    }
    init { setWillNotDraw(false); setBackgroundColor(palette.fieldFill); updateMarkers() }
    private fun updateMarkers() {
        buttons.forEachIndexed { index, button ->
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected == points[index]) palette.markerSelected else palette.marker)
                setStroke(if (selected == points[index]) 3 else 1,
                    if (selected == points[index]) palette.textPrimary else palette.outline)
            }
            button.isSelected = selected == points[index]
            button.setTextColor(palette.textOnMarker)
        }
    }
    private fun buttonDiameter(w: Int, h: Int): Int {
        val usable = h * .95
        val gap = min(4.0 * resources.displayMetrics.density, min(w, h) * .02)
        var diameter = min(w * .135, usable * .23)
        // Use one diameter for every point, limited by all edges and button gaps.
        for ((index, point) in points.withIndex()) {
            val edgeDistance = min(min(point.x, 1.0 - point.x) * w,
                min(point.y, 1.0 - point.y) * usable)
            diameter = min(diameter, edgeDistance * 2.0 - gap)
            for (other in points.drop(index + 1)) {
                val separation = max(abs(point.x - other.x) * w, abs(point.y - other.y) * usable)
                diameter = min(diameter, separation - gap)
            }
        }
        return diameter.toInt().coerceAtLeast(0)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val diameter = buttonDiameter(measuredWidth, measuredHeight)
        buttons.forEach { button ->
            // Keep the complete text block inside the circle's inscribed square.
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val lines = button.text.toString().split('\n')
            val textWidth = lines.maxOf { button.paint.measureText(it) }.coerceAtLeast(1f)
            val metrics = button.paint.fontMetrics
            val textHeight = (metrics.descent - metrics.ascent) * lines.size
            val textArea = (diameter * .70f - 2f * resources.displayMetrics.density).coerceAtLeast(1f)
            val scale = min(1f, min(textArea / textWidth, textArea / textHeight))
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, button.textSize * scale)
            val exact = View.MeasureSpec.makeMeasureSpec(diameter, View.MeasureSpec.EXACTLY)
            button.measure(exact, exact)
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val band = height * .05f
        val usable = height - band
        buttons.forEachIndexed { index, button ->
            val p = points[index]
            val w = button.measuredWidth
            val h = button.measuredHeight
            val cx = (width * (if (rightField) 1.0-p.x else p.x)).toInt()
                .coerceIn(w / 2, width - (w - w / 2))
            val cy = (band + usable * p.y).toInt()
            button.layout(cx-w/2,cy-h/2,cx-w/2+w,cy-h/2+h)
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val band = height * .05f
        val usable = height - band
        paint.color = palette.gridLine
        paint.alpha = 70
        paint.strokeWidth = resources.displayMetrics.density
        for (step in 1..3) {
            val x = width * step / 4f
            canvas.drawLine(x, band, x, height.toFloat(), paint)
            val y = band + (height - band) * step / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        paint.alpha = 255
        drawFieldLandmarks(canvas, band, usable)
        paint.color = palette.divider; canvas.drawRect(0f,0f,width.toFloat(),band,paint)
        paint.color = palette.textPrimary; paint.textSize = band * .66f
        canvas.drawText("共有ゾーン側", width/2f, band*.80f, paint)
    }
    private fun drawFieldLandmarks(canvas: Canvas, band: Float, usable: Float) {
        // The supplied field plan is rotated: start end at left, shared zone above.
        // Fixtures are schematic references, not recorded robot destinations.
        fun x(value: Float) = width * if (rightField) 1f - value else value
        fun y(value: Float) = band + usable * value
        fun box(cx: Float, cy: Float, w: Float, h: Float, round: Boolean = false) {
            val left = min(x(cx - w / 2), x(cx + w / 2))
            val right = max(x(cx - w / 2), x(cx + w / 2))
            paint.style = Paint.Style.FILL
            paint.color = palette.outline; paint.alpha = 24
            if (round) canvas.drawOval(left, y(cy - h / 2), right, y(cy + h / 2), paint)
            else canvas.drawRect(left, y(cy - h / 2), right, y(cy + h / 2), paint)
            paint.style = Paint.Style.STROKE
            paint.alpha = 180; paint.strokeWidth = resources.displayMetrics.density
            if (round) canvas.drawOval(left, y(cy - h / 2), right, y(cy + h / 2), paint)
            else canvas.drawRect(left, y(cy - h / 2), right, y(cy + h / 2), paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
        }
        fun label(text: String, cx: Float, cy: Float) {
            paint.color = palette.textSecondary
            paint.textSize = min(11f * resources.displayMetrics.scaledDensity, usable * .047f)
            canvas.drawText(text, x(cx), y(cy), paint)
        }
        // Positions and footprints follow the plan; dimensions are omitted for legibility.
        paint.color = palette.divider; paint.alpha = 28
        val startLeft = min(x(0f), x(.096f))
        val startRight = max(x(0f), x(.096f))
        canvas.drawRect(startLeft, y(.196f), startRight, y(.373f), paint)
        paint.alpha = 255
        box(.048f, .15f, .062f, .084f) // Replenishment spot.
        box(.028f, .95f, .056f, .08f) // Control station.
        box(.281f, .66f, .071f, .10f)
        box(.824f, .66f, .071f, .10f)
        label("机①", .281f, .79f)
        label("机②", .824f, .79f)
        box(.379f, .22f, .036f, .071f, round = true)
        box(.725f, .22f, .036f, .071f, round = true)
        box(.552f, .106f, .034f, .067f, round = true)
        label("固定バケツ②", .379f, .31f)
        label("固定バケツ③", .725f, .31f)
        label("固定バケツ①", .552f, .29f)
        box(.552f, .505f, .045f, .088f)
        paint.color = palette.textSecondary
        paint.strokeWidth = resources.displayMetrics.density * 1.5f
        canvas.drawLine(x(.552f), y(.505f), x(.552f), y(.44f), paint)
        canvas.drawLine(x(.552f), y(.44f), x(.573f), y(.462f), paint)
        label("旗", .552f, .60f)
        box(.552f, .868f, .042f, .09f)
        label("椅子", .552f, .975f)
        label("スタート側", .16f, .965f)
        label("奥側", .90f, .965f)
    }
    fun clearSelection() { selected = null; updateMarkers() }
    fun slotFor(point: SavedNavigationPoint) = point.slot + if (rightField) 8 else 0
}

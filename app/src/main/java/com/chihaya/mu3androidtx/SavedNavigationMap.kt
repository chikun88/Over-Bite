package com.chihaya.mu3androidtx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.min

/** Schematic normalized coordinates; actual positions are stored on Jetson. */
data class SavedNavigationPoint(
    val label: String, val jetsonName: String, val slot: Int,
    val x: Double, val y: Double,
)
const val SAVED_NAVIGATION_SIDE_SLOT_OFFSET = 8
val SAVED_NAVIGATION_POINTS = listOf(
    SavedNavigationPoint("自動装填", "A", 1, .055, .39),
    SavedNavigationPoint("バケツ2", "BAKETU2", 2, .34, .14),
    SavedNavigationPoint("バケツ3", "BAKETU3", 3, .70, .14),
    SavedNavigationPoint("旗下", "旗上側", 4, .475, .11),
    SavedNavigationPoint("旗上", "旗下側", 5, .555, .11),
    SavedNavigationPoint("退避位置", "退避位置", 6, .51, .83),
    SavedNavigationPoint("スタート\n位置", "装填位置", 7, .055, .18),
    SavedNavigationPoint("机保護1", "机保護1", 17, .25, .61),
    SavedNavigationPoint("机保護2", "机保護2", 18, .77, .61),
    SavedNavigationPoint("バケツ後2", "バケツ後2", 19, .34, .35),
    SavedNavigationPoint("バケツ後3", "バケツ後3", 20, .70, .35),
    SavedNavigationPoint("IDOU", "IDOU", 23, .18, .35),
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
            text = point.label; isAllCaps = false; textSize = 12f
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
    private fun diameterFor(point: SavedNavigationPoint, w: Int, h: Int): Int {
        val large = point.slot in listOf(1,6,7,17,18)
        return min(w * (if (large) .105f else .073f), h * .95f * (if (large) .20f else .165f)).toInt()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        buttons.forEachIndexed { index, button ->
            val size = diameterFor(points[index], measuredWidth, measuredHeight)
            button.textSize = (size / resources.displayMetrics.scaledDensity / 5f).coerceIn(8f,14f)
            val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            button.measure(exact, exact)
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val band = height * .05f
        val usable = height - band
        buttons.forEachIndexed { index, button ->
            val p = points[index]
            val diameter = diameterFor(p, width, height)
            val cx = (width * (if (rightField) 1.0-p.x else p.x)).toInt()
            val cy = (band + usable * p.y).toInt()
            button.layout(cx-diameter/2,cy-diameter/2,cx+diameter/2,cy+diameter/2)
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val band = height * .05f
        paint.color = palette.gridLine
        paint.strokeWidth = resources.displayMetrics.density
        for (step in 1..3) {
            val x = width * step / 4f
            canvas.drawLine(x, band, x, height.toFloat(), paint)
            val y = band + (height - band) * step / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        paint.color = palette.divider; canvas.drawRect(0f,0f,width.toFloat(),band,paint)
        paint.color = palette.textPrimary; paint.textSize = band * .66f
        canvas.drawText("フィールド中央", width/2f, band*.80f, paint)
        paint.color = palette.textSecondary; paint.textSize = height * .045f
        canvas.drawText("保存地点を選択", width/2f,height*.61f,paint)
    }
    fun clearSelection() { selected = null; updateMarkers() }
    fun slotFor(point: SavedNavigationPoint) = point.slot + if (rightField) 8 else 0
}

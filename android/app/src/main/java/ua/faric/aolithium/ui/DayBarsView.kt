package ua.faric.aolithium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import ua.faric.aolithium.core.Zones

/**
 * Найнижчий заряд по днях — стовпчики (faric 19.10 03:31: «хвилями… до порогу, потім генератор»).
 * Над стовпчиком — відсоток, під ним — число місяця; день з генератором — помаранчева рамка й «Г».
 * Торкнувся стовпчика — [onDay] (позначити / зняти «генератор»).
 */
class DayBarsView(context: Context) : View(context) {
    data class Bar(val dayLabel: String, val minSoc: Int, val generator: Boolean)

    private var bars: List<Bar> = emptyList()
    var onDay: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Palette.warning }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun set(list: List<Bar>) {
        bars = list
        contentDescription = "Найнижчий заряд по днях: " + list.joinToString("; ") {
            "${it.dayLabel} — ${it.minSoc} %" + if (it.generator) ", генератор" else ""
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (text.textSize * 4.6f + 70 * dp).toInt())
    }

    private val slot: Float get() = if (bars.isEmpty()) 1f else (width - paddingLeft - paddingRight).toFloat() / bars.size

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val top = text.textSize * 1.6f
        val bottom = height - text.textSize * 2.6f
        val barMax = bottom - top
        bars.forEachIndexed { i, b ->
            val x0 = paddingLeft + i * slot
            val w = (slot * 0.62f).coerceAtMost(30 * dp)
            val cx = x0 + slot / 2
            val h = (barMax * b.minSoc / 100f).coerceAtLeast(3 * dp)
            rect.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
            fill.color = GaugeView.toneColor(Zones.of(b.minSoc).tone)
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
            if (b.generator) canvas.drawRoundRect(rect, 4 * dp, 4 * dp, edge)
            text.color = Palette.muted
            canvas.drawText("${b.minSoc}", cx, bottom - h - text.textSize * 0.5f, text)
            text.color = if (b.generator) Palette.warning else Palette.faint
            canvas.drawText(b.dayLabel, cx, bottom + text.textSize * 1.3f, text)
            if (b.generator) canvas.drawText("Г", cx, bottom + text.textSize * 2.45f, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty() || onDay == null) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val i = ((event.x - paddingLeft) / slot).toInt()
            if (i in bars.indices) {
                performClick()
                onDay?.invoke(i)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

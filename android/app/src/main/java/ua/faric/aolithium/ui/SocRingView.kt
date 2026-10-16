package ua.faric.aolithium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Великий циферблат заряду: дуга 270°, посередині «93 %» і підпис.
 * Колір: звичайний — акцент; менше [LOW] — жовтий; менше [EMPTY] — червоний.
 */
class SocRingView(context: Context) : View(context) {
    var percent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            contentDescription = "Заряд $field відсотків"
            invalidate()
        }
    var caption: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val stroke = 14 * dp
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = Palette.track
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textAlign = Paint.Align.CENTER
        typeface = Ui.MEDIUM
        textSize = sp(46f)
        fontFeatureSettings = "tnum"
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textAlign = Paint.Align.CENTER
        typeface = Ui.REGULAR
        textSize = sp(14f)
    }
    private val box = RectF()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Альбомна: екран низький — циферблат менший, щоб під ним було видно числа (знімки CI 16.10).
        val maxDp = minOf(MAX_DP, (resources.configuration.screenHeightDp * 0.45f).toInt().coerceAtLeast(MIN_DP))
        val want = (maxDp * dp).toInt()
        val side = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) want else minOf(w, want)
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = side / 2f - stroke
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, START, SWEEP, false, track)
        arc.color = when {
            percent < EMPTY -> Palette.danger
            percent < LOW -> Palette.warning
            else -> Palette.accent
        }
        if (percent > 0) canvas.drawArc(box, START, SWEEP * percent / 100f, false, arc)
        // Текст не виходить за дугу навіть з великим шрифтом телефона (шрифт 1,3 — підпис налазив на дугу).
        val inner = (r - stroke) * 2f * 0.86f
        big.textSize = sp(46f)
        fit(big, "$percent %", inner)
        small.textSize = sp(14f)
        fit(small, caption, inner * 0.92f)
        val baseline = cy + big.textSize * 0.3f
        canvas.drawText("$percent %", cx, baseline, big)
        if (caption.isNotEmpty()) canvas.drawText(caption, cx, baseline + small.textSize * 1.8f, small)
    }

    private fun fit(p: Paint, text: String, maxWidth: Float) {
        val w = p.measureText(text)
        if (w > maxWidth && w > 0f) p.textSize = p.textSize * maxWidth / w
    }

    companion object {
        const val MAX_DP = 220
        const val MIN_DP = 140
        const val LOW = 20
        const val EMPTY = 10
        private const val START = 135f
        private const val SWEEP = 270f
    }
}

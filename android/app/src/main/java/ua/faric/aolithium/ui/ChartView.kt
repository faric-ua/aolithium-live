package ua.faric.aolithium.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.HistoryMath
import ua.faric.aolithium.core.tr

/**
 * Простий графік лініями за час. Кілька ліній; розриви (застосунок був закритий) — не з'єднуються.
 * [zeroSplit] — для струму: вище нуля лінія зелена (заряд), нижче — червона (розряд).
 * Дотик чи ведення пальцем убік — вертикальна риска й значення в цій точці.
 * T92: де між справжніми замірами пройшло більше хвилини ([sparse], `HistoryMath.sparseSpans`) — тонкий пунктир
 * з крапками на замірах: це лише здогадка, що було між ними (запис у фоні раз на 5 хв). Суцільно — точні дані.
 */
class ChartView(context: Context) : View(context) {
    class Series(val name: String, val color: Int, val points: List<Pair<Long, Double>?>)

    private var series: List<Series> = emptyList()
    private var from = 0L
    private var to = 1L
    private var unit = ""
    private var digits = 1
    private var zeroSplit = false
    private var fixedMin: Double? = null
    private var fixedMax: Double? = null
    private var gapMs = 60_000L
    private var sparse: List<LongRange> = emptyList()
    private var touchX = -1f

    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * dp; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.border; strokeWidth = 1f }
    /** T92: рідкі заміри — тонкий пунктир. */
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * dp; strokeCap = Paint.Cap.BUTT
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashPath = Path()
    private val dots = ArrayList<Float>()
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; strokeWidth = 1f * dp; pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.faint; textSize = sp(11f); fontFeatureSettings = "tnum" }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text; strokeWidth = 1f * dp }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.surfaceAlt }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12.5f); fontFeatureSettings = "tnum"; typeface = Ui.MEDIUM }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = sp(13f); textAlign = Paint.Align.CENTER }
    private val path = Path()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun set(
        series: List<Series>, from: Long, to: Long, unit: String, digits: Int,
        zeroSplit: Boolean = false, min: Double? = null, max: Double? = null, gapMs: Long = 60_000L,
        sparse: List<LongRange> = emptyList(),
    ) {
        this.gapMs = gapMs
        this.sparse = sparse
        this.series = series
        this.from = from
        this.to = max(to, from + 1)
        this.unit = unit
        this.digits = digits
        this.zeroSplit = zeroSplit
        this.fixedMin = min
        this.fixedMax = max
        contentDescription = tr("Графік: ", "Chart: ") + series.joinToString { it.name }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (HEIGHT_DP * dp).toInt())
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Дотик — курсор зі значенням. Рух пальцем убік — курсор іде за пальцем (сторінку не гортаємо);
     * рух угору-вниз — гортається сторінка, як завжди (інакше на вкладці «Графіки» не прогорнути).
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                touchX = e.x
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (!dragging && dx > slop && dx > dy) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) touchX = e.x
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) touchX = -1f // після ведення пальцем — сховати; після дотику — лишити, доки не торкнешся знову
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchX = -1f
                dragging = false
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val left = 44 * dp
        val right = width - 8 * dp
        val top = 10 * dp
        val bottom = height - 22 * dp
        val values = series.flatMap { s -> s.points.mapNotNull { it?.second } }
        if (values.isEmpty()) {
            canvas.drawText(tr("Ще немає записів за цей час", "No records for this period yet"), width / 2f, height / 2f, empty)
            return
        }
        var lo = fixedMin ?: values.min()
        var hi = fixedMax ?: values.max()
        if (zeroSplit) {
            lo = min(lo, 0.0)
            hi = max(hi, 0.0)
        }
        if (hi - lo < 1e-6) {
            hi += 1.0
            lo -= 1.0
        } else if (fixedMin == null || fixedMax == null) {
            val pad = (hi - lo) * 0.08
            if (fixedMax == null) hi += pad
            if (fixedMin == null) lo -= pad
        }
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(t: Long) = (left + (t - from).toDouble() / (to - from) * (right - left)).toFloat()

        // сітка й підписи
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(Fmt.num(v, digits), 2 * dp, yy + label.textSize / 3, label)
        }
        if (zeroSplit && lo < 0 && hi > 0) canvas.drawLine(left, y(0.0), right, y(0.0), zero)
        val fmt = SimpleDateFormat(if (to - from > 36 * 3_600_000L) "dd.MM" else "HH:mm", Locale.ROOT)
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(fmt.format(Date(from)), left, height - 6 * dp, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(fmt.format(Date(to)), right, height - 6 * dp, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(fmt.format(Date((from + to) / 2)), (left + right) / 2, height - 6 * dp, label)
        label.textAlign = Paint.Align.LEFT

        // лінії
        for (s in series) {
            if (zeroSplit) {
                drawLine(canvas, s.points, ::x, ::y) { it >= 0 }
            } else {
                line.color = s.color
                drawPath(canvas, s.points, ::x, ::y)
            }
        }

        // курсор
        if (touchX >= left) {
            val t = from + ((min(touchX, right) - left) / (right - left) * (to - from)).toLong()
            canvas.drawLine(touchX, top, touchX, bottom, cursor)
            val parts = series.mapNotNull { s ->
                s.points.filterNotNull().minByOrNull { abs(it.first - t) }?.let { s to it }
            }
            if (parts.isNotEmpty()) {
                val stamp = SimpleDateFormat(if (to - from > 36 * 3_600_000L) "dd.MM HH:mm" else "HH:mm:ss", Locale.ROOT).format(Date(parts[0].second.first))
                val lines = listOf(stamp to Palette.muted) + parts.map { (s, p) ->
                    (if (series.size > 1) s.name + ": " else "") + Fmt.num(p.second, digits) + " " + unit to
                        (if (zeroSplit) (if (p.second >= 0) Palette.success else Palette.danger) else s.color)
                }
                val w = lines.maxOf { bubbleText.measureText(it.first) } + 16 * dp
                val h = lines.size * bubbleText.textSize * 1.35f + 10 * dp
                val bx = if (touchX + w + 8 * dp < right) touchX + 8 * dp else touchX - w - 8 * dp
                canvas.drawRoundRect(bx, top, bx + w, top + h, 8 * dp, 8 * dp, bubble)
                lines.forEachIndexed { i, (txt, c) ->
                    bubbleText.color = c
                    canvas.drawText(txt, bx + 8 * dp, top + 5 * dp + bubbleText.textSize * (1.1f + i * 1.35f), bubbleText)
                }
            }
        }
    }

    /** Відрізок між сусідніми точками графіка — лише здогадка (між замірами більше хвилини)? */
    private fun sparseSeg(a: Long, b: Long) = HistoryMath.sparseBetween(sparse, a, b)

    /** Крапки на рідких замірах — лише якщо вони не злипаються в товсту лінію (≥ 5 dp одна від одної). */
    private fun addDot(px: Float, py: Float) {
        val n = dots.size
        if (n >= 2 && abs(dots[n - 2] - px) < 0.5f && abs(dots[n - 1] - py) < 0.5f) return
        dots += px
        dots += py
    }

    private fun flushDots(canvas: Canvas, color: Int) {
        dot.color = color
        var i = 0
        var lastX = -1e9f
        while (i < dots.size) {
            val px = dots[i]
            if (px - lastX >= 5 * dp) {
                canvas.drawCircle(px, dots[i + 1], 2.4f * dp, dot)
                lastX = px
            }
            i += 2
        }
        dots.clear()
    }

    /**
     * Лінія через точки; порожні шматки часу пропускаємо. Розрив — лише коли між сусідніми точками більше [gapMs]
     * (застосунок був закритий). Інакше на 1 год з точкою раз на 10 с лінія розсипалась на крапки (знімки CI 18.10).
     * T92: відрізки між рідкими замірами — пунктиром з крапками.
     */
    private fun drawPath(canvas: Canvas, pts: List<Pair<Long, Double>?>, x: (Long) -> Float, y: (Double) -> Float) {
        path.reset()
        dashPath.reset()
        dots.clear()
        var prev: Pair<Long, Double>? = null
        var segLen = 0
        var penDown = false // суцільна лінія зараз продовжується з prev
        for (p in pts) {
            if (p == null) continue
            val a = prev
            if (a == null || p.first - a.first > gapMs) {
                if (a != null && segLen == 1) canvas.drawPoint(x(a.first), y(a.second), line)
                path.moveTo(x(p.first), y(p.second))
                penDown = true
                segLen = 1
            } else if (sparseSeg(a.first, p.first)) {
                dashPath.moveTo(x(a.first), y(a.second))
                dashPath.lineTo(x(p.first), y(p.second))
                addDot(x(a.first), y(a.second))
                addDot(x(p.first), y(p.second))
                penDown = false
                segLen++
            } else {
                if (!penDown) path.moveTo(x(a.first), y(a.second))
                path.lineTo(x(p.first), y(p.second))
                penDown = true
                segLen++
            }
            prev = p
        }
        prev?.let { if (segLen == 1) canvas.drawPoint(x(it.first), y(it.second), line) }
        canvas.drawPath(path, line)
        dashed.color = line.color
        canvas.drawPath(dashPath, dashed)
        flushDots(canvas, line.color)
    }

    /** Струм: кожен відрізок свого кольору за знаком; розриви — як у [drawPath]; рідкі заміри — пунктир з крапками (T92). */
    private fun drawLine(canvas: Canvas, pts: List<Pair<Long, Double>?>, x: (Long) -> Float, y: (Double) -> Float, positive: (Double) -> Boolean) {
        var prev: Pair<Long, Double>? = null
        var lastDotX = -1e9f
        fun dotAt(px: Float, py: Float, color: Int) {
            if (px - lastDotX < 5 * dp) return
            dot.color = color
            canvas.drawCircle(px, py, 2.4f * dp, dot)
            lastDotX = px
        }
        for (p in pts) {
            if (p == null) continue
            val a = prev
            if (a != null && p.first - a.first <= gapMs) {
                val c = if (positive((a.second + p.second) / 2)) Palette.success else Palette.danger
                if (sparseSeg(a.first, p.first)) {
                    dashed.color = c
                    dashPath.reset()
                    dashPath.moveTo(x(a.first), y(a.second))
                    dashPath.lineTo(x(p.first), y(p.second))
                    canvas.drawPath(dashPath, dashed) // drawLine пунктир не малює — лише шлях
                    dotAt(x(a.first), y(a.second), if (positive(a.second)) Palette.success else Palette.danger)
                    dotAt(x(p.first), y(p.second), if (positive(p.second)) Palette.success else Palette.danger)
                } else {
                    line.color = c
                    canvas.drawLine(x(a.first), y(a.second), x(p.first), y(p.second), line)
                }
            } else {
                line.color = if (positive(p.second)) Palette.success else Palette.danger
                canvas.drawPoint(x(p.first), y(p.second), line)
            }
            prev = p
        }
    }

    companion object {
        const val HEIGHT_DP = 170
    }
}

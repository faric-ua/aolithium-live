package ua.faric.aolithium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.Zones
import ua.faric.aolithium.core.tr

/**
 * «Спідометр» заряду (faric 18.10 20:47): посередині «100 %», під ним потужність або струм ([showAmps],
 * перемикач «Вати / Ампери»). faric 19.10 03:02: віддає (світла нема) — число червоне і стрілка, що «тече» вгору
 * (енергія йде з батареї); заряджається — зелене, стрілка тече вниз (енергія йде в батарею). Обід поділено на зони
 * ([Zones]: червона / помаранчева / зелена / помаранчева), а **стрілка-трикутник іде по зовнішньому
 * контуру обода** й показує, де зараз заряд. Усередині обода — дуга заповнення кольору зони.
 */
class GaugeView(context: Context) : View(context) {
    var soc: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            describe()
            invalidate()
        }
    var current: Double = 0.0
        set(value) {
            field = value
            describe()
            invalidate()
        }
    var power: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var voltage: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    /** Велике число — ампери (true) чи вати (false, як просив faric — «скільки ват віддає»). */
    var showAmps: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    /** Даних ще нема: обід із зонами є, але без стрілки й заповнення, посередині «— %». */
    var noData: Boolean = false
        set(value) {
            field = value
            if (value) contentDescription = tr("Заряд невідомий — немає даних", "Charge unknown, no data")
            invalidate()
        }
    var caption: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val zoneStroke = 9 * dp
    private val fillStroke = 16 * dp
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = zoneStroke; strokeCap = Paint.Cap.BUTT }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = fillStroke; strokeCap = Paint.Cap.ROUND; color = Palette.track }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = fillStroke; strokeCap = Paint.Cap.ROUND }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.text }
    private val needleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = Palette.bg }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.faint; textAlign = Paint.Align.CENTER; textSize = sp(11f); fontFeatureSettings = "tnum" }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text; textAlign = Paint.Align.CENTER; typeface = Ui.MEDIUM; fontFeatureSettings = "tnum" }
    private val amp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.MEDIUM; fontFeatureSettings = "tnum" }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val box = RectF()
    private val path = Path()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun describe() {
        val dir = when {
            current > IDLE -> tr("заряджається ${Fmt.amps(current)}", "charging ${Fmt.amps(current)}")
            current < -IDLE -> tr("віддає ${Fmt.amps(-current)}", "discharging ${Fmt.amps(-current)}")
            else -> tr("струму майже нема", "almost no current")
        }
        val zone = Zones.of(soc).title.lowercase()
        contentDescription = tr("Заряд $soc відсотків, $zone, $dir", "Charge $soc percent, $zone, $dir")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxDp = minOf(MAX_DP, (resources.configuration.screenHeightDp * 0.5f).toInt().coerceAtLeast(MIN_DP))
        val want = (maxDp * dp).toInt()
        val side = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) want else minOf(w, want)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side
        // Дуга 240° — знизу відкрита, тож висота трохи менша за ширину.
        setMeasuredDimension(width, (side * 0.88f).toInt())
    }

    private fun angleOf(percent: Float) = START + SWEEP * percent / 100f

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width.toFloat(), height / 0.88f)
        val cx = width / 2f
        val cy = side / 2f + 4 * dp
        val outer = side / 2f - 14 * dp // місце для трикутника зовні
        val rZone = outer - zoneStroke / 2
        val rFill = outer - zoneStroke - 6 * dp - fillStroke / 2

        // зони на ободі
        box.set(cx - rZone, cy - rZone, cx + rZone, cy + rZone)
        for (z in Zones.ALL) {
            zonePaint.color = toneColor(z.tone)
            val from = angleOf(z.from.toFloat())
            val to = angleOf(minOf(z.to, 100).toFloat())
            canvas.drawArc(box, from + 0.6f, to - from - 1.2f, false, zonePaint)
        }

        // заповнення
        box.set(cx - rFill, cy - rFill, cx + rFill, cy + rFill)
        canvas.drawArc(box, START, SWEEP, false, track)
        // Заповнення — блакитне; лише внизу шкали (мало / глибокий розряд) фарбується кольором зони як попередження.
        fill.color = if (soc < 20) toneColor(Zones.of(soc).tone) else Palette.accent
        if (soc > 0 && !noData) canvas.drawArc(box, START, SWEEP * soc / 100f, false, fill)

        // поділки 0 / 20 / 50 / 90 / 100 — всередині, під дугою заповнення. Центр підпису відсуваємо від дуги на
        // половину його ширини/висоти в напрямку дуги — інакше широке «100» торкалось дуги (скрін faric 19.10 04:41, T18).
        val rTick = rFill - fillStroke / 2 - 12 * dp
        val rEdge = rFill - fillStroke / 2 - 5 * dp
        for (v in TICKS) {
            val a = Math.toRadians(angleOf(v.toFloat()).toDouble())
            val halfW = tick.measureText("$v") / 2
            val halfH = tick.textSize * 0.38f
            val r = minOf(rTick, rEdge - halfW * kotlin.math.abs(cos(a)).toFloat() - halfH * kotlin.math.abs(sin(a)).toFloat())
            canvas.drawText("$v", cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat() + halfH, tick)
        }

        if (noData) {
            big.textSize = sp(44f)
            canvas.drawText("— %", cx, cy + big.textSize * 0.15f, big)
            return
        }

        // стрілка-трикутник ззовні обода, вістрям до центру
        val a = Math.toRadians(angleOf(soc.toFloat()).toDouble())
        val tipR = outer + 1 * dp
        val baseR = outer + 13 * dp
        val half = Math.toRadians(4.5)
        path.reset()
        path.moveTo(cx + (tipR * cos(a)).toFloat(), cy + (tipR * sin(a)).toFloat())
        path.lineTo(cx + (baseR * cos(a - half)).toFloat(), cy + (baseR * sin(a - half)).toFloat())
        path.lineTo(cx + (baseR * cos(a + half)).toFloat(), cy + (baseR * sin(a + half)).toFloat())
        path.close()
        canvas.drawPath(path, needle)
        canvas.drawPath(path, needleEdge)

        // текст у центрі. T18 (скрін faric 19.10 04:41): рядки «13,36 В · стоїть» і «92,2 з 92,3 А·год» сиділи один
        // на одному — підпис стояв за геометрією дуги, а 3-й рядок — за висотою шрифтів. Тепер спершу рахуємо всі
        // рядки по черзі (кожен нижче попереднього), а якщо низ не влазить — увесь блок піднімаємо.
        val inner = (rTick - 10 * dp) * 2f
        big.textSize = sp(48f)
        fit(big, "$soc %", inner * 0.9f)
        var yBig = cy + big.textSize * 0.15f

        val flow = when {
            current > IDLE -> 1
            current < -IDLE -> -1
            else -> 0
        }
        val color = when (flow) {
            1 -> Palette.success
            -1 -> Palette.danger
            else -> Palette.muted
        }
        amp.color = color
        amp.textSize = sp(24f)
        val absA = kotlin.math.abs(current)
        val absW = kotlin.math.abs(power)
        val mainText = if (showAmps) Fmt.amps(absA) else Fmt.watts(absW)
        val arrowW = if (flow != 0) 22 * dp else 0f
        fit(amp, mainText, inner * 0.78f - arrowW)
        var yAmp = yBig + amp.textSize * 1.3f

        // 3-й рядок — друге число, напруга й напрям
        small.textSize = sp(13f)
        val other = if (showAmps) Fmt.watts(absW) else Fmt.amps(absA)
        val line3 = when (flow) {
            1 -> "$other · " + tr("бере заряд", "charging")
            -1 -> "$other · " + tr("віддає", "discharging")
            else -> if (voltage > 0) Fmt.volts(voltage) + " · " + tr("стоїть", "idle") else tr("стоїть", "idle")
        }
        fit(small, line3, inner * 0.72f)
        val size3 = small.textSize
        var y3 = yAmp + size3 * 1.5f

        // 4-й рядок — підпис (А·год) між кінцями дуги, але завжди нижче 3-го рядка на повний рядок
        val capPaint = Paint(small)
        var yCap = 0f
        if (caption.isNotEmpty()) {
            capPaint.textSize = sp(13f)
            val gapWidth = (2 * rFill * cos(Math.toRadians(30.0))).toFloat() * 0.7f
            fit(capPaint, caption, gapWidth)
            val geometric = cy + (rFill * sin(Math.toRadians(30.0))).toFloat() + fillStroke / 2 + capPaint.textSize
            yCap = maxOf(geometric, y3 + size3 * 0.35f + capPaint.textSize * LINE_GAP)
        }
        val bottom = (if (caption.isNotEmpty()) yCap else y3) + small.textSize * 0.3f
        val overflow = bottom - (height - 2 * dp)
        if (overflow > 0) {
            yBig -= overflow
            yAmp -= overflow
            y3 -= overflow
            yCap -= overflow
        }

        canvas.drawText("$soc %", cx, yBig, big)
        val textW = amp.measureText(mainText)
        val xText = cx - arrowW / 2
        canvas.drawText(mainText, xText, yAmp, amp)
        if (flow != 0) drawFlowArrow(canvas, xText + textW / 2 + 13 * dp, yAmp - amp.textSize * 0.36f, flow, color)
        small.textSize = size3
        canvas.drawText(line3, cx, y3, small)
        if (caption.isNotEmpty()) canvas.drawText(caption, cx, yCap, capPaint)
    }

    /**
     * Стрілка, що «тече»: [dir] −1 — вгору (батарея віддає), 1 — вниз (заряджається). Повільно рухається й тане;
     * якщо в телефоні вимкнено анімації — стоїть на місці.
     */
    private fun drawFlowArrow(canvas: Canvas, x: Float, yMid: Float, dir: Int, color: Int) {
        val h = 18 * dp
        val travel = 7 * dp
        val animate = android.animation.ValueAnimator.areAnimatorsEnabled()
        val phase = if (animate) (android.os.SystemClock.uptimeMillis() % FLOW_MS) / FLOW_MS.toFloat() else 0.5f
        // dir −1 (вгору): зміщення від +travel до −travel; dir 1 (вниз): навпаки
        val shift = (phase - 0.5f) * 2 * travel * (if (dir < 0) -1 else 1)
        val alpha = if (animate) (255 * (1 - kotlin.math.abs(phase - 0.5f) * 2).coerceIn(0.25f, 1f)).toInt() else 255
        arrowPaint.color = color
        arrowPaint.alpha = alpha
        val top = yMid - h / 2 + shift
        val bottom = yMid + h / 2 + shift
        val head = 5.5f * dp
        canvas.drawLine(x, top, x, bottom, arrowPaint)
        if (dir < 0) {
            canvas.drawLine(x, top, x - head, top + head, arrowPaint)
            canvas.drawLine(x, top, x + head, top + head, arrowPaint)
        } else {
            canvas.drawLine(x, bottom, x - head, bottom - head, arrowPaint)
            canvas.drawLine(x, bottom, x + head, bottom - head, arrowPaint)
        }
        if (animate && isShown) postInvalidateDelayed(FRAME_MS)
    }

    private fun fit(p: Paint, text: String, maxWidth: Float) {
        val w = p.measureText(text)
        if (w > maxWidth && w > 0f) p.textSize = p.textSize * maxWidth / w
    }

    companion object {
        const val MAX_DP = 300
        const val MIN_DP = 170
        const val IDLE = 0.05
        /** Відстань між базовими лініями 3-го і 4-го рядків — у висотах шрифту (T18: було ~0,9 — налазили). */
        private const val LINE_GAP = 1.1f
        private const val FLOW_MS = 1600L
        private const val FRAME_MS = 40L
        private const val START = 150f
        private const val SWEEP = 240f
        private val TICKS = listOf(0, 20, 50, 90, 100)

        fun toneColor(t: Zones.Tone) = when (t) {
            Zones.Tone.RED -> Palette.danger
            Zones.Tone.ORANGE -> Palette.warning
            Zones.Tone.GREEN -> Palette.success
        }
    }
}

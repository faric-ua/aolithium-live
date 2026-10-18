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

/**
 * «Спідометр» заряду (faric 18.10 20:47): посередині «100 %», під ним струм зі стрілкою
 * (▲ зелений — заряджається, ▼ червоний — віддає) і потужність; обід поділено на зони
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
    private val box = RectF()
    private val path = Path()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun describe() {
        val dir = when {
            current > IDLE -> "заряджається ${Fmt.amps(current)}"
            current < -IDLE -> "віддає ${Fmt.amps(-current)}"
            else -> "струму майже нема"
        }
        contentDescription = "Заряд $soc відсотків, ${Zones.of(soc).title.lowercase()}, $dir"
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
        if (soc > 0) canvas.drawArc(box, START, SWEEP * soc / 100f, false, fill)

        // поділки 0 / 20 / 50 / 90 / 100 — всередині, під дугою заповнення
        val rTick = rFill - fillStroke / 2 - 12 * dp
        for (v in TICKS) {
            val a = Math.toRadians(angleOf(v.toFloat()).toDouble())
            canvas.drawText("$v", cx + (rTick * cos(a)).toFloat(), cy + (rTick * sin(a)).toFloat() + tick.textSize / 3, tick)
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

        // текст у центрі
        val inner = (rTick - 10 * dp) * 2f
        big.textSize = sp(48f)
        fit(big, "$soc %", inner * 0.9f)
        val yBig = cy + big.textSize * 0.15f
        canvas.drawText("$soc %", cx, yBig, big)

        val (arrow, color) = when {
            current > IDLE -> "▲ +" to Palette.success
            current < -IDLE -> "▼ −" to Palette.danger
            else -> "" to Palette.muted
        }
        amp.color = color
        amp.textSize = sp(19f)
        val ampText = arrow + Fmt.amps(kotlin.math.abs(current))
        fit(amp, ampText, inner * 0.8f)
        val yAmp = yBig + amp.textSize * 1.35f
        canvas.drawText(ampText, cx, yAmp, amp)

        // 3-й рядок — потужність (коли є струм); ємність — окремо внизу, у прорізі між кінцями дуги,
        // щоб не налазити на поділки «0» і «100» (знімки CI 18.10).
        small.textSize = sp(13f)
        if (kotlin.math.abs(current) > IDLE) {
            val w = Fmt.watts(kotlin.math.abs(power))
            fit(small, w, inner * 0.6f)
            canvas.drawText(w, cx, yAmp + small.textSize * 1.45f, small)
        }
        if (caption.isNotEmpty()) {
            small.textSize = sp(13f)
            val gapWidth = (2 * rFill * cos(Math.toRadians(30.0))).toFloat() * 0.7f
            fit(small, caption, gapWidth)
            val yCap = cy + (rFill * sin(Math.toRadians(30.0))).toFloat() + fillStroke / 2 + small.textSize
            canvas.drawText(caption, cx, minOf(yCap, height - 4 * dp), small)
        }
    }

    private fun fit(p: Paint, text: String, maxWidth: Float) {
        val w = p.measureText(text)
        if (w > maxWidth && w > 0f) p.textSize = p.textSize * maxWidth / w
    }

    companion object {
        const val MAX_DP = 300
        const val MIN_DP = 170
        const val IDLE = 0.05
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

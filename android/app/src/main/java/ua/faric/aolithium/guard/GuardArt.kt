package ua.faric.aolithium.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.Guard
import ua.faric.aolithium.core.GuardPicture
import ua.faric.aolithium.core.StaleShow
import ua.faric.aolithium.core.WidgetModel
import ua.faric.aolithium.core.Zones
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.ui.GaugeView
import ua.faric.aolithium.ui.Palette
import ua.faric.aolithium.ui.Ui
import ua.faric.aolithium.widget.WidgetArt

/**
 * T59: картинки батареї для бота сторожа — з тих самих малюнків, що й застосунок:
 *  - «як спідометр» — `GaugeView` вкладки «Зараз» (зони, стрілка, вати), над ним назва й час, під ним зона й стан;
 *  - «як віджет» — `WidgetArt` великого віджета;
 *  - «віджет + комірки» — віджет і плитки комірок з різницею (найнижча — жовта рамка, найвища — блакитна);
 *  - «стан усіх батарей» — сітка малих віджетів з назвами (кілька батарей одного сторожа).
 *
 * Малюємо на головному потоці (там живуть View), сторож чекає до [WAIT_MS]; не вийшло — повідомлення піде без картинки.
 */
object GuardArt {
    private const val W_DP = 360
    private const val FOOT_DP = 26f
    private const val WAIT_MS = 5_000L

    fun battery(ctx: Context, look: GuardPicture.Look, name: String, s: BatterySnapshot?, at: Long?, now: Long): Bitmap? =
        if (look == GuardPicture.Look.NONE) null else onMain {
            when (look) {
                GuardPicture.Look.GAUGE -> if (s == null || at == null) widget(ctx, name, s, at, now) else gauge(ctx, name, s, at, now)
                GuardPicture.Look.WIDGET -> widget(ctx, name, s, at, now)
                GuardPicture.Look.CELLS -> cells(ctx, name, s, at, now)
                GuardPicture.Look.NONE -> null
            }
        }

    /** Кілька батарей — сітка; одна — як віджет (з назвою). */
    fun grid(ctx: Context, batteries: List<Guard.Seen>, now: Long): Bitmap? = onMain {
        if (batteries.size == 1) {
            val b = batteries[0]
            widget(ctx, b.name, b.snapshot, b.at, now)
        } else {
            gridArt(ctx, batteries, now)
        }
    }

    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    // ---------- вигляди ----------

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density.coerceIn(1.5f, 3f)

    private fun widget(ctx: Context, name: String, s: BatterySnapshot?, at: Long?, now: Long): Bitmap {
        val d = density(ctx)
        val pad = (12 * d).toInt()
        val art = WidgetArt.draw(WidgetModel.of(name, s, at, now), W_DP, 170, d)
        val temp = s?.let { GuardPicture.tempLine(it.temperatures) }
        val foot = if (temp != null) FOOT_DP * d else 0f
        return frame(art.width + 2 * pad, (art.height + 2 * pad + foot).toInt()) { c ->
            c.drawBitmap(art, pad.toFloat(), pad.toFloat(), null)
            if (temp != null) footer(c, temp, pad.toFloat(), pad + art.height.toFloat(), art.width.toFloat(), d)
        }
    }

    private fun gauge(ctx: Context, name: String, s: BatterySnapshot, at: Long, now: Long): Bitmap {
        val d = density(ctx)
        val w = (W_DP * d).toInt()
        val pad = 16 * d
        val inner = (w - 2 * pad).toInt()
        val view = GaugeView(ctx).apply {
            soc = s.soc
            current = s.current
            power = s.power
            voltage = s.voltage
            caption = Fmt.num(s.remainAh, 1) + tr(" з ", " of ") + Fmt.num(s.fullAh, 1) + " " + Fmt.AH
            temperature = s.temperatures.maxOrNull() // T67: над «NN %», як у застосунку
            // T69: сторож давно не бачив батарею — останні дані сірим з «дані від …», без живої стрілки струму
            blink = false
            staleLabel = if (now - at > WidgetModel.STALE_MS) StaleShow.label(at, now) else null
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val gh = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, inner, gh)
        val model = WidgetModel.of(name, s, at, now)
        val zone = Zones.of(s.soc)
        val head = 30 * d
        val lines = listOf(zone.title to GaugeView.toneColor(zone.tone), model.status to statusColor(model.tone), model.line1 to Palette.muted)
            // T65 рядок «температура …» тут не потрібен: з T67 вона над «NN %» у самому спідометрі
            .filter { it.first.isNotBlank() }
        val lineH = 22 * d
        val h = (pad + head + gh + 8 * d + lines.size * lineH + pad).toInt()
        return frame(w, h) { c ->
            header(c, name, model, at, now, pad, pad, w - pad, d)
            c.save()
            c.translate(pad, pad + head)
            view.draw(c)
            c.restore()
            var y = pad + head + gh + 8 * d
            lines.forEachIndexed { i, (text, color) ->
                val p = text(if (i == 0) 15f else 13.5f, d, color, medium = i < 2, center = true)
                c.drawText(fit(text, p, w - 2 * pad), w / 2f, y + p.textSize, p)
                y += lineH
            }
        }
    }

    private fun cells(ctx: Context, name: String, s: BatterySnapshot?, at: Long?, now: Long): Bitmap {
        val d = density(ctx)
        val pad = (12 * d).toInt()
        val art = WidgetArt.draw(WidgetModel.of(name, s, at, now), W_DP, 170, d)
        val mv = s?.cellsMv.orEmpty()
        if (mv.isEmpty()) return widget(ctx, name, s, at, now)
        val temp = s?.let { GuardPicture.tempLine(it.temperatures) }
        val foot = if (temp != null) FOOT_DP * d else 0f
        val w = art.width + 2 * pad
        val cols = min(4, mv.size)
        val rows = ceil(mv.size / cols.toDouble()).toInt()
        val gap = 8 * d
        val tileH = 58 * d
        val title = 30 * d
        val h = (pad + art.height + foot + gap + title + rows * tileH + (rows - 1) * gap + pad).toInt()
        return frame(w, h) { c ->
            c.drawBitmap(art, pad.toFloat(), pad.toFloat(), null)
            if (temp != null) footer(c, temp, pad.toFloat(), pad + art.height.toFloat(), art.width.toFloat(), d)
            var y = pad + art.height + foot + gap
            val delta = s?.deltaMv
            val verdict = s?.deltaVerdict
            val t = tr("Комірки", "Cells") + (if (delta != null) " · " + tr("різниця", "difference") + " " + Fmt.millivolts(delta) +
                (verdict?.let { " (${it.title})" } ?: "") else "")
            val tp = text(14f, d, Palette.text, medium = true)
            c.drawText(fit(t, tp, w - 2f * pad), pad.toFloat(), y + tp.textSize * 1.1f, tp)
            y += title
            val tileW = (w - 2 * pad - (cols - 1) * gap) / cols
            // різниця 1 мВ — шум, без рамок; однакові найнижчі / найвищі — рамка на всіх (як у застосунку, T24, T33)
            val marks = (delta ?: 0) > 1
            val balancing = s?.balancingCells.orEmpty().toSet()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.surface }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
            val num = text(12f, d, Palette.muted)
            val v = text(17f, d, Palette.text, medium = true)
            mv.forEachIndexed { i, cell ->
                val x = pad + (i % cols) * (tileW + gap)
                val ty = y + (i / cols) * (tileH + gap)
                val r = RectF(x, ty, x + tileW, ty + tileH)
                c.drawRoundRect(r, 12 * d, 12 * d, fill)
                edge.color = when {
                    marks && cell == mv.min() -> Palette.warning
                    marks && cell == mv.max() -> Palette.accent
                    (i + 1) in balancing -> Palette.violet
                    else -> Palette.border
                }
                c.drawRoundRect(r, 12 * d, 12 * d, edge)
                c.drawText("№${i + 1}", x + 10 * d, ty + 8 * d + num.textSize, num)
                c.drawText(fit(Fmt.cellVolts(cell), v, tileW - 20 * d), x + 10 * d, ty + tileH - 12 * d, v)
            }
        }
    }

    private fun gridArt(ctx: Context, list: List<Guard.Seen>, now: Long): Bitmap {
        val d = density(ctx)
        val pad = 12 * d
        val gap = 10 * d
        val cols = 2
        val tileDp = 170
        val art = list.map { WidgetArt.draw(WidgetModel.of(it.name, it.snapshot, it.at, now), tileDp, tileDp, d) }
        val tileW = art.maxOf { it.width }
        val tileH = art.maxOf { it.height }
        val nameH = 24 * d
        val title = 34 * d
        val rows = ceil(list.size / cols.toDouble()).toInt()
        val w = (2 * pad + cols * tileW + (cols - 1) * gap).toInt()
        val h = (pad + title + rows * (nameH + tileH) + (rows - 1) * gap + pad).toInt()
        return frame(w, h) { c ->
            val tp = text(17f, d, Palette.text, medium = true)
            c.drawText(fit(tr("Стан батарей", "Batteries") + " · " + Background.hhmm(now), tp, w - 2 * pad), pad, pad + tp.textSize, tp)
            val np = text(13.5f, d, Palette.muted, medium = true)
            list.forEachIndexed { i, b ->
                val x = pad + (i % cols) * (tileW + gap)
                val y = pad + title + (i / cols) * (nameH + tileH + gap)
                c.drawText(fit(b.name, np, tileW.toFloat()), x, y + np.textSize, np)
                c.drawBitmap(art[i], x, y + nameH, null)
            }
        }
    }

    // ---------- допоміжне ----------

    private fun header(c: Canvas, name: String, m: WidgetModel.Model, at: Long, now: Long, x: Float, y: Float, right: Float, d: Float) {
        val stale = now - at > WidgetModel.STALE_MS
        val timeText = if (stale) tr("не бачу з ${Background.hhmm(at)}", "not seen since ${Background.hhmm(at)}")
            else tr("о ${Background.hhmm(at)}", "at ${Background.hhmm(at)}")
        val tp = text(13.5f, d, if (stale) Palette.warning else Palette.muted)
        tp.textAlign = Paint.Align.RIGHT
        val tw = tp.measureText(timeText)
        c.drawText(timeText, right, y + tp.textSize * 1.2f, tp)
        val np = text(17f, d, Palette.text, medium = true)
        c.drawText(fit(m.name.ifBlank { name }, np, right - x - tw - 12 * d), x, y + np.textSize * 1.1f, np)
    }

    /** T65: рядок під картинкою віджета (температура) — по центру, приглушений. */
    private fun footer(c: Canvas, line: String, x: Float, top: Float, width: Float, d: Float) {
        val p = text(13.5f, d, Palette.muted, center = true)
        c.drawText(fit(line, p, width), x + width / 2f, top + 6 * d + p.textSize, p)
    }

    private fun statusColor(t: WidgetModel.Tone) = when (t) {
        WidgetModel.Tone.DISCHARGE -> Palette.danger
        WidgetModel.Tone.CHARGE -> Palette.success
        WidgetModel.Tone.STALE -> Palette.warning
        else -> Palette.muted
    }

    private fun frame(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Palette.bg)
        draw(c)
        return b
    }

    private fun text(sizeDp: Float, d: Float, color: Int, medium: Boolean = false, center: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeDp * d
            this.color = color
            typeface = if (medium) Ui.MEDIUM else Ui.REGULAR
            fontFeatureSettings = "tnum"
            if (center) textAlign = Paint.Align.CENTER
        }

    private fun fit(text: String, p: TextPaint, width: Float): String {
        val base = p.textSize
        while (p.measureText(text) > width && p.textSize > base * 0.8f) p.textSize *= 0.95f
        return TextUtils.ellipsize(text, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
    }

    /** Виконати на головному потоці й дочекатись (або одразу, якщо ми вже на ньому). */
    private fun <T> onMain(block: () -> T?): T? {
        val safe = {
            try {
                block()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return safe()
        var out: T? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            out = safe()
            done.countDown()
        }
        return if (done.await(WAIT_MS, TimeUnit.MILLISECONDS)) out else null
    }
}

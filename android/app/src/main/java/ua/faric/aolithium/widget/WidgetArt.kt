package ua.faric.aolithium.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import ua.faric.aolithium.core.WidgetModel
import ua.faric.aolithium.ui.Palette
import kotlin.math.min

/**
 * Малюнок віджета (v0.3.1) — як у прев'ю (екран 9): кільце із зарядом, ліворуч; праворуч — назва, потужність зі
 * стрілкою (червона ▲ віддає, зелена ▼ заряд), стан, «вистачить на», час запису. Вузький / квадратний віджет —
 * малий варіант: кільце зверху, під ним число й один рядок.
 *
 * Малюємо картинкою, а не з готових елементів віджета: так кольори лише з `Palette`, кільце як у застосунку,
 * і однаково на Android 8 і 16. Розмір — з того, скільки місця дав домашній екран.
 */
object WidgetArt {
    private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** [wDp] × [hDp] — місце від домашнього екрана; [density] — пікселів на dp. Великий малюнок зменшуємо (ліміт віджета). */
    fun draw(m: WidgetModel.Model, wDp: Int, hDp: Int, density: Float): Bitmap {
        val maxSide = 1100f
        val d = min(density, min(maxSide / wDp.coerceAtLeast(1), maxSide / hDp.coerceAtLeast(1))).coerceAtLeast(1f)
        val w = (wDp * d).toInt().coerceAtLeast(1)
        val h = (hDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.surface }
        val inset = d
        val r = RectF(inset, inset, w - inset, h - inset)
        c.drawRoundRect(r, 24 * d, 24 * d, card)
        c.drawRoundRect(r, 24 * d, 24 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = d
            color = Palette.border
        })
        if (wDp >= hDp * 1.5f) wide(c, m, w.toFloat(), h.toFloat(), d) else square(c, m, w.toFloat(), h.toFloat(), d)
        return bmp
    }

    private fun powerColor(t: WidgetModel.Tone) = when (t) {
        WidgetModel.Tone.DISCHARGE -> Palette.danger
        WidgetModel.Tone.CHARGE -> Palette.success
        else -> Palette.muted
    }

    private fun statusColor(t: WidgetModel.Tone) = when (t) {
        WidgetModel.Tone.DISCHARGE -> Palette.danger
        WidgetModel.Tone.CHARGE -> Palette.success
        WidgetModel.Tone.STALE -> Palette.warning
        else -> Palette.muted
    }

    private fun paint(sizePx: Float, color: Int, medium: Boolean = false, center: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (medium) MEDIUM else REGULAR
            fontFeatureSettings = "tnum"
            if (center) textAlign = Paint.Align.CENTER
        }

    private fun fit(text: String, p: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    /** Кільце із зарядом і відсотком посередині. */
    private fun ring(c: Canvas, m: WidgetModel.Model, cx: Float, cy: Float, diameter: Float, scale: Float, showNote: Boolean) {
        val stroke = 10f * scale
        val rad = diameter / 2 - stroke / 2
        val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Palette.track
        }
        c.drawOval(oval, p)
        if (m.ring > 0f) {
            p.color = if (m.tone == WidgetModel.Tone.STALE || m.tone == WidgetModel.Tone.EMPTY) Palette.faint else Palette.accent
            c.drawArc(oval, -90f, 360f * m.ring, false, p)
        }
        val pctColor = if (m.tone == WidgetModel.Tone.STALE || m.tone == WidgetModel.Tone.EMPTY) Palette.muted else Palette.text
        val pct = paint(diameter * 0.25f, pctColor, medium = true, center = true)
        if (showNote && m.ringNote.isNotBlank()) {
            val note = paint(diameter * 0.1f, Palette.muted, center = true)
            c.drawText(m.percent, cx, cy + pct.textSize * 0.2f, pct)
            c.drawText(fit(m.ringNote, note, diameter * 0.7f), cx, cy + pct.textSize * 0.2f + note.textSize * 1.4f, note)
        } else {
            c.drawText(m.percent, cx, cy + pct.textSize * 0.35f, pct)
        }
    }

    /** Стрілка: ▲ «тече» вгору — віддає; ▼ — заряджається. */
    private fun arrow(c: Canvas, a: WidgetModel.Arrow, x: Float, baseline: Float, size: Float, color: Int) {
        if (a == WidgetModel.Arrow.NONE) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.13f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val top = baseline - size * 0.95f
        val bottom = baseline - size * 0.05f
        val mid = x + size * 0.35f
        val half = size * 0.3f
        val path = Path()
        path.moveTo(mid, top)
        path.lineTo(mid, bottom)
        if (a == WidgetModel.Arrow.UP) {
            path.moveTo(mid - half, top + half)
            path.lineTo(mid, top)
            path.lineTo(mid + half, top + half)
        } else {
            path.moveTo(mid - half, bottom - half)
            path.lineTo(mid, bottom)
            path.lineTo(mid + half, bottom - half)
        }
        c.drawPath(path, p)
    }

    /** Великий (4 × 2): кільце ліворуч, текст праворуч. */
    private fun wide(c: Canvas, m: WidgetModel.Model, w: Float, h: Float, d: Float) {
        val pad = 16 * d
        val scale = min(1.4f, (h / d) / 170f)
        val diameter = min(h - 2 * pad, w * 0.36f)
        ring(c, m, pad + diameter / 2, h / 2, diameter, diameter / (118 * d) * d, showNote = true)
        val x = pad + diameter + 14 * d
        val width = w - x - pad
        val f = d * scale
        val name = paint(13 * f, Palette.muted)
        val power = paint((if (m.tone == WidgetModel.Tone.STALE || m.tone == WidgetModel.Tone.EMPTY) 22 else 28) * f, powerColor(m.tone), medium = true)
        val status = paint(14 * f, statusColor(m.tone), medium = true)
        val small = paint(13 * f, Palette.muted)
        val lines = listOf(name to m.name, power to m.power, status to m.status, small to m.line1, small to m.line2)
            .filter { it.second.isNotBlank() }
        val gap = 5 * f
        val total = lines.sumOf { (it.first.textSize * 1.15f).toDouble() }.toFloat() + gap * (lines.size - 1)
        var y = (h - total) / 2
        for ((p, text) in lines) {
            val lineH = p.textSize * 1.15f
            val baseline = y + p.textSize
            if (p === power) {
                val arrowW = if (m.arrow == WidgetModel.Arrow.NONE) 0f else p.textSize * 0.8f
                val t = fit(text, p, width - arrowW)
                c.drawText(t, x, baseline, p)
                arrow(c, m.arrow, x + p.measureText(t) + 6 * d, baseline, p.textSize * 0.75f, p.color)
            } else {
                c.drawText(fit(text, p, width), x, baseline, p)
            }
            y += lineH + gap
        }
    }

    /** Малий (2 × 2): кільце зверху, під ним число зі стрілкою й один рядок. */
    private fun square(c: Canvas, m: WidgetModel.Model, w: Float, h: Float, d: Float) {
        val pad = 12 * d
        val f = d * min(1.4f, min(w, h) / d / 170f)
        val power = paint(18 * f, powerColor(m.tone), medium = true, center = true)
        val small = paint(11.5f * f, Palette.muted, center = true)
        val textH = power.textSize * 1.2f + small.textSize * 1.3f + 6 * d
        val diameter = min(w - 2 * pad, h - 2 * pad - textH).coerceAtLeast(24 * d)
        val top = (h - diameter - textH) / 2
        ring(c, m, w / 2, top + diameter / 2, diameter, diameter / (96 * d) * d, showNote = false)
        var y = top + diameter + 6 * d + power.textSize
        val arrowW = if (m.arrow == WidgetModel.Arrow.NONE) 0f else power.textSize * 0.8f
        val t = fit(m.power, power, w - 2 * pad - arrowW)
        val tw = power.measureText(t)
        c.drawText(t, w / 2 - arrowW / 2, y, power)
        arrow(c, m.arrow, w / 2 - arrowW / 2 + tw / 2 + 4 * d, y, power.textSize * 0.75f, power.color)
        y += small.textSize * 1.3f
        c.drawText(fit(m.small, small, w - 2 * pad), w / 2, y, small)
    }
}

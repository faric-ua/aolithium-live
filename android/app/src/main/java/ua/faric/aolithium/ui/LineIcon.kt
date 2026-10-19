package ua.faric.aolithium.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import ua.faric.aolithium.core.SvgPath

/**
 * Значки лініями — той самий спосіб, що в Track Catcher (рамка 24×24, лінія 1,8, круглі кінці;
 * частини з [Part.filled] — заливка). Малюються кодом: без картинок і бібліотек, будь-який розмір і колір.
 * Код малювання (`SvgPath`, `LineIconDrawable`) — копія з Track Catcher; набір значків — свій.
 */
enum class LineIcon(vararg val parts: Part) {
    BATTERY(
        Part("M3 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
        Part("M22 10v4"),
        Part("M6.5 9.5v5M10 9.5v5M13.5 9.5v5"),
    ),
    BLUETOOTH(Part("M7 7l10 10-5 4V3l5 4L7 17")),
    BOLT(Part("M13 2L5 14h6l-1 8 8-12h-6z")),
    THERMO(Part("M10 13.5V5a2 2 0 0 1 4 0v8.5a4 4 0 1 1-4 0z"), Part("M12 9v7")),
    CELLS(Part("M3 7h4v10H3zM10 7h4v10h-4zM17 7h4v10h-4z"), Part("M4 4h2M11 4h2M18 4h2")),
    SEARCH(Part(SvgPath.circle(10.5f, 10.5f, 6.5f)), Part("M15.5 15.5L21 21")),
    BACK(Part("M15 5l-7 7 7 7")),
    PULSE(Part("M3 12h4l3-7 4 14 3-7h4")),
    SHIELD(Part("M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"), Part("M12 8v5M12 16.5v.5")),
    CHIP(Part("M7 7h10v10H7z"), Part("M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4")),
    REPEAT(Part("M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3"), Part("M18 3v4h-4M6 21v-4h4")),
    INFO(Part(SvgPath.circle(12f, 12f, 9f)), Part("M12 11v6M12 7.5v.3")),
    HELP(Part(SvgPath.circle(12f, 12f, 9f)), Part("M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7M12 17v.3")),
    BOOK(Part("M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2z"), Part("M4 20a2 2 0 0 0 2 2h13v-4M9 7h6")),
    SLIDERS(
        Part("M4 7h9M17 7h3M4 17h3M11 17h9"),
        Part(SvgPath.circle(15f, 7f, 2f)), Part(SvgPath.circle(9f, 17f, 2f)),
    ),
    PEN(Part("M4 20h4L19 9l-4-4L4 16z"), Part("M13.5 6.5l4 4")),
    CHART(Part("M4 20V4M4 20h16"), Part("M7 15l4-5 3 3 5-7")),
    DOTS(Part(SvgPath.circle(5f, 12f, 1.6f), filled = true), Part(SvgPath.circle(12f, 12f, 1.6f), filled = true), Part(SvgPath.circle(19f, 12f, 1.6f), filled = true)),
    GAUGE(Part("M4.5 17a8.5 8.5 0 1 1 15 0"), Part("M12 13l4-5")),
    BELL(Part("M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z"), Part("M10 21a2 2 0 0 0 4 0")),
    LIST(
        Part("M8 6h12M8 12h12M8 18h12"),
        Part(SvgPath.circle(4f, 6f, 1f)), Part(SvgPath.circle(4f, 12f, 1f)), Part(SvgPath.circle(4f, 18f, 1f)),
    ),
    ;

    class Part(val d: String, val filled: Boolean = false) {
        val path: Path by lazy { toPath(SvgPath.parse(d)) }
    }

    /** Значок для [android.widget.TextView] чи [android.widget.ImageView]. */
    fun drawable(color: Int, sizePx: Int): LineIconDrawable = LineIconDrawable(this, color).apply { setBounds(0, 0, sizePx, sizePx) }

    companion object {
        /** Символ, на місці якого в тексті стоїть значок ([iconText]). */
        const val OBJ = '\uFFFC'

        // Розміри значків, dp — як у Track Catcher (faric 16.10 16:25: «збільшуємо відсотків на 60–70»).
        const val TAB_DP = 36
        const val LABEL_DP = 28
        const val BUTTON_DP = 31
        const val BUTTON_LARGE_DP = 36

        fun toPath(segs: List<SvgPath.Seg>): Path = Path().apply {
            for (s in segs) when (s) {
                is SvgPath.Seg.Move -> moveTo(s.x, s.y)
                is SvgPath.Seg.Line -> lineTo(s.x, s.y)
                is SvgPath.Seg.Cubic -> cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
                SvgPath.Seg.Close -> close()
            }
        }
    }
}

/** Малює [LineIcon] у своїх межах: масштаб 24 → ширина меж. Колір — [color]. */
class LineIconDrawable(private val icon: LineIcon, color: Int) : Drawable() {
    var color: Int = color
        set(value) {
            field = value
            stroke.color = value
            fill.color = value
            invalidateSelf()
        }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        val save = canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        for (p in icon.parts) canvas.drawPath(p.path, if (p.filled) fill else stroke)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Drawable.getOpacity")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Текст зі значком на початку: «[значок] Нічний екран». [above] — значок над текстом (вкладки).
 * Значок — [ImageSpan] по центру рядка, тож у кнопці посередині він стоїть поруч з текстом.
 */
fun Ui.iconText(icon: LineIcon, label: CharSequence, color: Int, sizeDp: Int = 18, above: Boolean = false): CharSequence =
    SpannableStringBuilder().apply {
        append(LineIcon.OBJ)
        setSpan(ImageSpan(icon.drawable(color, dp(sizeDp)), DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(if (above) "\n" else " ")
        append(label)
    }

/** Підпис без значка — для озвучення й порівнянь. */
fun plainLabel(text: CharSequence): String = text.toString().replace(LineIcon.OBJ.toString(), "").trim()

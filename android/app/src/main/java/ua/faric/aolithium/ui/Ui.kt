package ua.faric.aolithium.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Будівельні блоки інтерфейсу (як у Track Catcher). Кожен новий елемент — звідси.
 * Ніяких стандартних сірих діалогів і кнопок Android.
 */
class Ui(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    /** Дві колонки — за реальною шириною (правило Renault), не просто за поворотом. */
    val twoPane: Boolean get() = ctx.resources.configuration.screenWidthDp >= TWO_PANE_DP

    // ---------- фон ----------

    fun rounded(fill: Int, stroke: Int = Palette.transparent, radiusDp: Int = 14, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Palette.transparent) setStroke(dp(strokeDp), stroke)
        }

    fun pressable(base: Drawable, radiusDp: Int = 14): Drawable =
        RippleDrawable(ColorStateList.valueOf(Palette.ripple), base, rounded(Palette.text, radiusDp = radiusDp))

    // ---------- текст ----------

    fun text(s: CharSequence, sizeSp: Float = 16f, color: Int = Palette.text, medium: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = if (medium) MEDIUM else REGULAR
            setLineSpacing(0f, 1.15f)
        }

    fun title(s: CharSequence, sizeSp: Float = 20f): TextView = text(s, sizeSp, Palette.text, medium = true)

    fun muted(s: CharSequence, sizeSp: Float = 13f): TextView = text(s, sizeSp, Palette.muted)

    /** Підпис розділу: дрібні великі літери з розрядкою; [icon] — значок кольору акценту перед ним. */
    fun label(s: String, icon: LineIcon? = null): TextView = text(s.uppercase(), 11.5f, Palette.muted, medium = true).apply {
        letterSpacing = 0.08f
        if (icon != null) {
            text = iconText(icon, s.uppercase(), Palette.accent, sizeDp = LineIcon.LABEL_DP)
            contentDescription = s
        }
    }

    fun numbers(tv: TextView): TextView = tv.apply { fontFeatureSettings = "tnum" }

    // ---------- контейнери ----------

    fun column(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    fun row(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    private fun gap(gapDp: Int) = GradientDrawable().apply {
        setColor(Palette.transparent)
        setSize(dp(gapDp), dp(gapDp))
    }

    fun card(gapDp: Int = 10, fill: Int = Palette.surface, stroke: Int = Palette.border): LinearLayout = column(gapDp).apply {
        background = rounded(fill, stroke, radiusDp = 16)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    fun scroll(child: View, padDp: Int = 16): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp + 8))
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float, vertical: Boolean = false): LinearLayout.LayoutParams =
        if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    // ---------- кнопки ----------

    enum class Kind { PRIMARY, NORMAL, DANGER, QUIET }

    /** Кнопка: мінімум 48 dp, текст переноситься (до 3 рядків) — довгі українські підписи не обрізаються. */
    fun button(label: String, kind: Kind = Kind.NORMAL, large: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            typeface = MEDIUM
            textSize = if (large) 17f else 15f
            minHeight = dp(if (large) 56 else MIN_TOUCH_DP)
            minimumHeight = dp(if (large) 56 else MIN_TOUCH_DP)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            styleButton(this, kind)
        }

    /** Кнопка зі значком перед підписом (значок — кольору тексту кнопки). */
    fun iconButton(icon: LineIcon, label: String, kind: Kind = Kind.NORMAL, large: Boolean = false, onClick: () -> Unit): TextView =
        button(label, kind, large, onClick).apply {
            text = iconText(icon, label, currentTextColor, sizeDp = if (large) LineIcon.BUTTON_LARGE_DP else LineIcon.BUTTON_DP)
            contentDescription = label
        }

    fun styleButton(b: TextView, kind: Kind) {
        val (fill, stroke, color) = when (kind) {
            Kind.PRIMARY -> Triple(Palette.accentFill, Palette.accent, Palette.accent)
            Kind.DANGER -> Triple(Palette.dangerFill, Palette.danger, Palette.danger)
            Kind.NORMAL -> Triple(Palette.surfaceAlt, Palette.border, Palette.text)
            Kind.QUIET -> Triple(Palette.transparent, Palette.border, Palette.muted)
        }
        b.setTextColor(color)
        b.background = pressable(rounded(fill, stroke, radiusDp = 12), 12)
    }

    fun setEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f
    }

    /** Вибір з кількох (як у Track Catcher): вибране — зелена рамка 2 dp, білий жирний текст. Без галочок. */
    fun choice(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 2
        textSize = 15f
        minHeight = dp(MIN_TOUCH_DP)
        minimumHeight = dp(MIN_TOUCH_DP)
        minWidth = dp(64)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        isFocusable = true
        isSelected = selected
        setOnClickListener { onClick() }
        typeface = if (selected) MEDIUM else REGULAR
        setTextColor(if (selected) Palette.text else Palette.muted)
        background = pressable(
            if (selected) rounded(Palette.selectedFill, Palette.success, radiusDp = 12, strokeDp = 2)
            else rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12),
            12,
        )
        contentDescription = if (selected) "$label · вибрано" else label
    }

    /**
     * Кнопки на всю ширину (faric 18.10 23:49: «кнопки на всю ширину скрізь, де можна; де можна — ділити рядок
     * на дві кнопки»): по [perRow] в рядку, однакової ширини й висоти; остання непарна — на весь рядок.
     * Порядок зберігається: «Скасувати / Закрити» лишається останньою, тобто праворуч або внизу.
     */
    fun grid(views: List<View>, perRow: Int = 2, gapDp: Int = 8): LinearLayout {
        val box = column(gapDp)
        views.chunked(perRow.coerceAtLeast(1)).forEach { chunk ->
            val row = row(gapDp).apply {
                gravity = Gravity.FILL_VERTICAL
                isBaselineAligned = false // різні підписи в одному рядку не зсувають кнопки (урок Renault)
            }
            for (v in chunk) {
                (v.parent as? ViewGroup)?.removeView(v)
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            box.addView(row, matchWrap())
        }
        return box
    }

    /** Стан — кольорова «пігулка» зі словом (не лише колір). */
    fun pill(label: String, tone: Tone): TextView = text(label, 12.5f, tone.color, medium = true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(5))
        background = rounded(tone.fill, radiusDp = 20)
    }

    enum class Tone(val color: Int, val fill: Int) {
        GOOD(Palette.success, Palette.selectedFill),
        WARN(Palette.warning, Palette.warningFill),
        BAD(Palette.danger, Palette.dangerFill),
        INFO(Palette.accent, Palette.accentFill),
        VIOLET(Palette.violet, Palette.violetFill),
        NEUTRAL(Palette.muted, Palette.surfaceAlt),
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(Palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2))
    }

    /** Рядок «назва — значення». */
    /**
     * Рядок «назва — значення». Обидві частини мають свою частку ширини й переносяться словами:
     * раніше значення брало скільки хотіло, і довге («сигнал −91 dBm, …») вилазило за край,
     * а назви ламались посеред слова («Заряджен-о») — відео faric 19.10.
     */
    fun fact(name: String, value: CharSequence, valueColor: Int = Palette.text): LinearLayout = row(12).apply {
        gravity = Gravity.TOP
        addView(muted(name, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(numbers(text(keepUnits(value), 15f, valueColor, medium = true)).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
    }

    companion object {
        /** Число й одиниця не розриваються переносом: «63,5 А·год», а не «63,5 / А·год» (знімки CI, шрифт 1,3). */
        fun keepUnits(v: CharSequence): String = v.toString().replace(Regex("""(\d) (?=[А-Яа-яІіЇїЄєA-Za-z°%])"""), "$1\u00A0")  // uk-only

        const val MIN_TOUCH_DP = 48
        const val TWO_PANE_DP = 600
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

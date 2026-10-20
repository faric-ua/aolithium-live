package ua.faric.aolithium.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.ViewTreeObserver
import android.widget.ScrollView

/**
 * Власні вікна поверх екрана (замість стандартних сірих діалогів Android).
 * Кожне вікно має ключ — після повороту екрана Activity відновлює його за ключем.
 * Копія з Track Catcher (номери T41, T51, T53 у коментарях — задачі того репо); кнопки вікон — на всю ширину ([Ui.grid]).
 *
 * **T47 (faric 20.10 22:53):** у кожному вікні заголовок завжди видно згори, кнопки керування — завжди знизу, а
 * прокручується лише середина. Хоч який довгий текст — будь-яку кнопку можна натиснути будь-коли, і видно, що це за вікно.
 * Як: у вмісті вікна шапку й кнопки позначено ([header], [footer]; [dialog] робить це сам), а [show] виносить їх
 * з прокрутки нагору й донизу. Середина — `ScrollView` з вагою 1: вікно не вище за екран, середина стискається.
 */
class Overlay(private val host: FrameLayout, private val ui: Ui, private val onChange: (String?) -> Unit) {
    var key: String? = null
        private set
    private var layer: View? = null
    private var panel: View? = null
    private var scroll: ScrollView? = null
    private var scrollWatch: ViewTreeObserver.OnGlobalLayoutListener? = null
    /** Системні панелі (рядок стану згори, кнопки внизу): вікно не заходить під них (T41, faric 14.10). */
    private var barLeft = 0
    private var barTop = 0
    private var barRight = 0
    private var barBottom = 0

    val isShowing: Boolean get() = layer != null

    /**
     * [keepScroll] — нове вікно замість такого самого (⏮/⏭ у подробицях запису, T51): прокрутити так,
     * щоб низ (плеєр і кнопки) лишився на тому самому місці екрана, а не стрибав угору.
     */
    fun show(key: String, content: View, dismissOnOutside: Boolean = true, keepScroll: Boolean = false) {
        val fromBottom = if (keepScroll) scroll?.let { distanceFromBottom(it) } else null
        dismiss(notify = false)
        this.key = key
        val scrim = FrameLayout(host.context).apply {
            setBackgroundColor(Palette.scrim)
            isClickable = true
            setOnClickListener { if (dismissOnOutside) dismiss() }
        }
        val panel = ui.column().apply {
            background = ui.rounded(Palette.surface, Palette.border, radiusDp = 20)
            isClickable = true // дотик по вікну не закриває його
            elevation = ui.dp(12).toFloat()
        }
        // T47: шапку й кнопки — з прокрутки нагору й донизу (у тому ж порядку, як були у вмісті).
        val (headers, footers) = takePinned(content)
        val top = if (headers.isEmpty()) null else ui.column(8).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(10))
            headers.forEach { addView(it.first, it.second) }
        }
        val bottom = if (footers.isEmpty()) null else ui.column(8).apply {
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(18))
            footers.forEach { addView(it.first, it.second) }
        }
        val scroll = ScrollView(host.context).apply {
            isFillViewport = false
            setPadding(ui.dp(20), if (top == null) ui.dp(18) else ui.dp(4), ui.dp(20), if (bottom == null) ui.dp(18) else ui.dp(4))
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Риски між шапкою / серединою / кнопками — лише коли середина справді прокручується (інакше вікно суцільне).
        val lineTop = top?.let { ui.divider() }
        val lineBottom = bottom?.let { ui.divider() }
        top?.let { panel.addView(it, ui.matchWrap()) }
        lineTop?.let { panel.addView(it) }
        // Висота 0 + вага 1: якщо все влазить — середина за вмістом; не влазить — забирає лише залишок між шапкою й кнопками.
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        lineBottom?.let { panel.addView(it) }
        bottom?.let { panel.addView(it, ui.matchWrap()) }
        val watch = ViewTreeObserver.OnGlobalLayoutListener {
            val scrolls = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
            val v = if (scrolls) View.VISIBLE else View.INVISIBLE
            lineTop?.let { if (it.visibility != v) it.visibility = v }
            lineBottom?.let { if (it.visibility != v) it.visibility = v }
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(watch)
        scrollWatch = watch
        val widthDp = host.context.resources.configuration.screenWidthDp
        val panelW = minOf(ui.dp(560), (ui.dp(widthDp) * 0.92f).toInt())
        scrim.addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = scrim
        this.panel = panel
        this.scroll = scroll
        placePanel()
        fromBottom?.let { restoreFromBottom(scroll, it) }
        onChange(key)
    }

    /** Вікно на весь екран без рамки (нічний екран). */
    fun showFullScreen(key: String, content: View) {
        dismiss(notify = false)
        this.key = key
        content.isClickable = true
        host.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = content
        onChange(key)
    }

    /** Відступи системних панелей змінились (поворот, клавіатура) — переставити вікно. */
    fun setSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
        barLeft = left
        barTop = top
        barRight = right
        barBottom = bottom
        placePanel()
    }

    private fun placePanel() {
        val p = panel ?: return
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        // Вікно — між системними панелями з запасом 16 dp; вище не влазить — прокручується всередині.
        lp.leftMargin = barLeft
        lp.rightMargin = barRight
        lp.topMargin = barTop + ui.dp(16)
        lp.bottomMargin = barBottom + ui.dp(16)
        p.layoutParams = lp
    }

    fun dismiss(notify: Boolean = true) {
        scrollWatch?.let { w -> scroll?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(w) }
        scrollWatch = null
        layer?.let { host.removeView(it) }
        layer = null
        panel = null
        scroll = null
        key = null
        if (notify) onChange(null)
    }

    /** Типове вікно: заголовок, текст, кнопки праворуч (скасування/«Закрити» — найправіша: YTM UX-018, faric T53). */
    fun dialog(
        title: String,
        message: CharSequence,
        actions: List<Pair<String, Ui.Kind>>,
        onAction: (Int) -> Unit,
        extra: View? = null,
    ): View {
        val box = ui.column(14)
        box.addView(header(ui.title(title, 19f)))
        if (message.isNotEmpty()) box.addView(ui.text(message, 15f, Palette.muted))
        extra?.let { box.addView(it) }
        // Кнопки на всю ширину вікна, рівними частинами; «Закрити/Скасувати» — остання, тобто найправіша (T53).
        val buttons = actions.mapIndexed { i, (label, kind) -> ui.button(label, kind) { onAction(i) } }
        box.addView(footer(ui.grid(buttons, perRow = buttons.size.coerceAtMost(2))), ui.matchWrap())
        return box
    }

    companion object {
        private const val TAG_HEADER = "overlay:header"
        private const val TAG_FOOTER = "overlay:footer"

        /** T47: позначити заголовок вікна — він стоятиме згори й не прокручуватиметься. */
        fun <V : View> header(v: V): V = v.apply { tag = TAG_HEADER }

        /** T47: позначити кнопки вікна — вони стоятимуть знизу й не прокручуватимуться. */
        fun <V : View> footer(v: V): V = v.apply { tag = TAG_FOOTER }

        /** Вийняти з вмісту позначені шапку й кнопки (лише прямі діти) разом з їхніми параметрами розміщення. */
        private fun takePinned(content: View): Pair<List<Pair<View, ViewGroup.LayoutParams>>, List<Pair<View, ViewGroup.LayoutParams>>> {
            val g = content as? ViewGroup ?: return emptyList<Pair<View, ViewGroup.LayoutParams>>() to emptyList()
            val heads = ArrayList<Pair<View, ViewGroup.LayoutParams>>()
            val feet = ArrayList<Pair<View, ViewGroup.LayoutParams>>()
            fun params(c: View): ViewGroup.LayoutParams = when (val lp = c.layoutParams) {
                is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp) // зберегти gravity (кнопка праворуч)
                null -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else -> LinearLayout.LayoutParams(lp)
            }
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                when (c.tag) {
                    TAG_HEADER -> heads += c to params(c)
                    TAG_FOOTER -> feet += c to params(c)
                }
            }
            (heads + feet).forEach { g.removeView(it.first) }
            return heads to feet
        }
    }
}

/** Скільки прокрутки лишилось до низу вмісту (T51: тримати низ на місці при заміні вмісту). */
fun distanceFromBottom(s: ScrollView): Int = (s.getChildAt(0)?.height ?: 0) - s.scrollY

/** Після побудови нового вмісту — прокрутити так, щоб до низу лишилось [fromBottom] (до першого кадру, без блимання). */
fun restoreFromBottom(s: ScrollView, fromBottom: Int) {
    s.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (s.viewTreeObserver.isAlive) s.viewTreeObserver.removeOnPreDrawListener(this)
            val h = s.getChildAt(0)?.height ?: return true
            s.scrollTo(0, (h - fromBottom).coerceAtLeast(0))
            return true
        }
    })
}

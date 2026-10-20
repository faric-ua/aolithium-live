package ua.faric.aolithium.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ua.faric.aolithium.bg.Notifier
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.ble.BatteryLink
import ua.faric.aolithium.ble.BleScanner
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.ble.DemoLink
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.Alerts
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.CellTrend
import ua.faric.aolithium.core.ChargerCheck
import ua.faric.aolithium.core.DailyMin
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.Glossary
import ua.faric.aolithium.core.Health
import ua.faric.aolithium.core.HistoryCsv
import ua.faric.aolithium.core.HistoryMath
import ua.faric.aolithium.core.HistoryPoint
import ua.faric.aolithium.core.JbdParser
import ua.faric.aolithium.core.Laggard
import ua.faric.aolithium.core.Lang
import ua.faric.aolithium.core.LinkLine
import ua.faric.aolithium.core.Outages
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.SteadyCurrent
import ua.faric.aolithium.core.TextFit
import ua.faric.aolithium.core.TimeLeft
import ua.faric.aolithium.core.WidgetModel
import ua.faric.aolithium.core.Zones
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.AlertSettings
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.HistoryStore
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.data.Marks
import ua.faric.aolithium.widget.WidgetArt
import ua.faric.aolithium.widget.Widgets

/**
 * Одна батарея (v0.2.0, faric 18.10 20:47: «внизу менюшечка, по групам поділено… графіки… спідометр»).
 *
 * Унизу — 5 вкладок: «Зараз» (спідометр, головні числа, стан плати), «Комірки» (комірки й температури),
 * «Графіки» (історія за 15 хв … 7 днів і підсумки), «Плата» (здоров'я, плата, лічильники захистів),
 * «Ще» (дії, довідник по кожному показнику, історія, довідка). Кожен рядок з «ⓘ» відкриває докладне пояснення.
 *
 * Читання кожні 2 с, поки екран відкритий; раз на 10 с — запис в історію (`HistoryStore`).
 * Сховав застосунок — через 5 с відключаємось (батарея вільна для інших телефонів).
 */
class BatteryScreen(host: MainActivity, private val address: String, state: Bundle?) : Screen(host), BatteryLink.Listener {
    // T12: підпис — getter (не аргумент enum), щоб після перемикання мови не лишився старий.
    enum class Tab(val icon: LineIcon) {
        NOW(LineIcon.GAUGE),
        CELLS(LineIcon.CELLS),
        CHARTS(LineIcon.CHART),
        BOARD(LineIcon.CHIP),
        MORE(LineIcon.DOTS);

        val label: String get() = when (this) {
            NOW -> tr("Зараз", "Now")
            CELLS -> tr("Комірки", "Cells")
            CHARTS -> tr("Графіки", "Charts")
            BOARD -> tr("Плата", "BMS")
            MORE -> tr("Ще", "More")
        }
    }

    enum class Range(val ms: Long) {
        M15(15 * 60_000L),
        H1(3_600_000L),
        H6(6 * 3_600_000L),
        D1(86_400_000L),
        D7(7 * 86_400_000L);

        val label: String get() = when (this) {
            M15 -> tr("15 хв", "15 min")
            H1 -> tr("1 год", "1 h")
            H6 -> tr("6 год", "6 h")
            D1 -> tr("24 год", "24 h")
            D7 -> tr("7 днів", "7 days")
        }
    }

    private val batteries = Batteries(host)
    private val bg = BgSettings(host)
    private val alertPrefs = AlertSettings(host)
    private val history = HistoryStore.get(host)
    private val demo = DemoLink.sceneOf(address)
    private var link: BatteryLink? = null
    /** Лише для знімків CI в демо (ключ «status:…»): стан зв'язку не міняється сам. */
    private var frozenStatus: LinkStatus? = null
    private var status = LinkStatus.IDLE
    private var snapshot: BatterySnapshot? = null
    private var snapshotAt = 0L
    private val lastStore = LastSnapshot(host)
    /** Останнє збережене (коли зараз зв'язку нема) — faric 18.10 21:48: «щоб можна було по всьому додатку лазити». */
    private val stale: Pair<BatterySnapshot, Long>? = if (demo == null) lastStore.load(address) else null

    /** Що показуємо: живі числа, останні збережені чи порожньо (прочерки). */
    private enum class Data { LIVE, STALE, NONE }

    private val data: Data
        get() = when {
            snapshot != null -> Data.LIVE
            stale != null -> Data.STALE
            else -> Data.NONE
        }
    private var remembered = false
    private var renameDraft = state?.getString(KEY_DRAFT)
    private var tab = state?.getString(KEY_TAB)?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: Tab.NOW
    private var range = state?.getString(KEY_RANGE)?.let { n -> Range.entries.firstOrNull { it.name == n } } ?: Range.H1
    private var points: List<HistoryPoint> = emptyList()
    private var pointsFor: Range? = null
    private var pointsLoadedAt = 0L
    private var historyBytes: Pair<Int, Long>? = null
    private val marks = Marks(host)
    /** Струм для «вистачить на» — середній, не скаче (faric 19.10 03:02). */
    private val steady = SteadyCurrent()
    /** Живі виміри комірок за ~3 хв — для «за 1 хв». */
    private val cellSamples = ArrayDeque<Pair<Long, List<Int>>>()
    /** Історія за [INSIGHT_DAYS] днів — відключення, найнижчий по днях, хто відстає, зарядний. */
    private var insightPts: List<HistoryPoint>? = null
    private var insightLoadedAt = 0L
    /** Розмір підписів у плитках комірок, якщо довелось зменшити (шрифт 1.3); 0 — звичайний. */
    private var miniCaptionPx = 0f
    /** T30: спільний розмір значень у плитках підсумків (0 — ще не зменшували) і самі значення поточного ряду. */
    private var summaryValuePx = 0f
    private val summaryValues = ArrayList<TextView>()
    /** Остання година — графік різниці на «Комірках». */
    private var hourPts: List<HistoryPoint>? = null
    private var hourLoadedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val head = ui.column(6)
    private val body = ui.column(14)
    // Заголовок гортається разом з вмістом: в альбомній екран низький (знімки CI 18.10 — для карток лишалась смужка).
    private val scroll: ScrollView = ui.scroll(ui.column(10).apply {
        addView(head, ui.matchWrap())
        addView(body, ui.matchWrap())
    })
    private val tabButtons = LinkedHashMap<Tab, TextView>()
    /** Рядок «Зв'язок» (T25): будується один раз, далі міняються лише слова й колір — висота стала. */
    private val linkPill = ui.pill("", Ui.Tone.NEUTRAL).apply { maxLines = 1 }
    private val linkDetail = oneLine(ui.muted("", 13f))
    private val linkSecond = oneLine(ui.muted("", 12.5f))
    private val linkRetry = ui.iconButton(LineIcon.REPEAT, tr("Ще раз", "Retry"), Ui.Kind.PRIMARY) { reconnect() }.apply {
        maxLines = 1
        textSize = 14f
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    }
    private val linkStrip: View = buildLinkStrip()
    override val view: View

    private val ticker = object : Runnable {
        override fun run() {
            updateLinkStrip()
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        val root = ui.column()
        root.addView(scroll, ui.weight(1f, vertical = true))
        root.addView(tabBar(), ui.matchWrap())
        view = root
        render()
    }

    private val title: String
        get() = demo?.let { tr("Демо: ${it.title}", "Demo: ${it.title}") }
            ?: batteries.find(address)?.title
            ?: Batteries.scanNames[address]
            ?: BmsMatch.shortAddress(address)

    // ---------- життєвий цикл ----------

    override fun onStart() {
        val l = LinkHolder.acquire(host, address)
        link = l
        l.listener = this
        status = frozenStatus ?: l.lastStatus
        l.lastSnapshot?.let { snapshot = it; snapshotAt = l.lastSnapshotAt; feed(it, l.lastSnapshotAt) }
        render()
        handler.post(ticker)
        if (tab == Tab.CHARTS) loadInsights()
        if (tab == Tab.CELLS) loadHour()
    }

    override fun onAccessChanged() {
        if (tab == Tab.MORE) render()
    }

    override fun onResume() {
        // Повернулись із системного вікна (батарея без обмежень, сповіщення) — оновити картку «Запис у фоні».
        if (tab == Tab.MORE) render()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        LinkHolder.release(this)
    }

    override fun save(out: Bundle) {
        renameDraft?.let { out.putString(KEY_DRAFT, it) }
        out.putString(KEY_TAB, tab.name)
        out.putString(KEY_RANGE, range.name)
    }

    override fun restoreDialog(key: String): Boolean {
        when {
            key == DLG_RENAME -> showRename()
            key == DLG_LOG -> showLog()
            key == DLG_LINK -> showLinkInfo()
            key == DLG_FORGET -> showForget()
            key == DLG_CLEAR -> showClearHistory()
            key.startsWith(DLG_INFO) -> showInfo(key.removePrefix(DLG_INFO))
            key == DLG_WIDGETS -> showWidgetPreview()
            // CI, лише демо: «status:LOST» — заморозити стан зв'язку, щоб зняти всі стани (T25: спідометр не їздить)
            key.startsWith("status:") && demo != null -> {
                val p = LinkStatus.Phase.entries.firstOrNull { it.name == key.removePrefix("status:") } ?: return false
                val retrying = p == LinkStatus.Phase.LOST || p == LinkStatus.Phase.CONNECTING
                frozenStatus = LinkStatus(p, if (p == LinkStatus.Phase.FAILED) DemoLink.FAILED_SAMPLE else "",
                    if (retrying) 2 else 0, if (retrying) 3 else 0)
                onStatus(frozenStatus!!)
            }
            // debug/CI: «tab:CHARTS» або «tab:CHARTS:D1» (вкладка й період графіків)
            key.startsWith("tab:") -> {
                val parts = key.split(':')
                parts.getOrNull(2)?.let { r -> Range.entries.firstOrNull { it.name == r } }?.let { range = it; pointsFor = null }
                Tab.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { selectTab(it) }
            }
            else -> return false
        }
        return true
    }

    // ---------- BatteryLink.Listener ----------

    override fun onStatus(status: LinkStatus) {
        this.status = frozenStatus ?: status
        renderHead()
        if (tab != Tab.CHARTS) render()
    }

    override fun onSnapshot(snapshot: BatterySnapshot, atMs: Long) {
        this.snapshot = snapshot
        snapshotAt = atMs
        feed(snapshot, atMs)
        if (!remembered && demo == null) {
            remembered = true
            batteries.remember(address, Batteries.scanNames[address] ?: snapshot.model, Batteries.kindOf(host, address))
        }
        history.record(address, snapshot, atMs)
        lastStore.save(address, snapshot, atMs)
        if (demo == null) {
            Notifier.onSnapshot(host, address, snapshot, atMs)
            Widgets.onData(host, fromScreen = true)
        }
        if (tab == Tab.CHARTS) {
            // графіки не перемальовуємо щосекунди — лише підвантажуємо нові точки раз на 30 с
            if (atMs - pointsLoadedAt > 30_000) loadHistory()
            if (atMs - insightLoadedAt > INSIGHT_RELOAD_MS) loadInsights()
            renderHead()
        } else {
            if (tab == Tab.CELLS && atMs - hourLoadedAt > 60_000) loadHour()
            render()
        }
    }

    private fun feed(s: BatterySnapshot, atMs: Long) {
        steady.add(atMs, s.current)
        if (s.cellsMv.isNotEmpty() && cellSamples.lastOrNull()?.first != atMs) {
            cellSamples.addLast(atMs to s.cellsMv)
            while (cellSamples.isNotEmpty() && cellSamples.first().first < atMs - 180_000L) cellSamples.removeFirst()
        }
    }

    // ---------- каркас ----------

    private fun tabBar(): View {
        val bar = ui.row(6).apply {
            if (ui.twoPane) setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) else setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8))
            setBackgroundColor(Palette.surface)
        }
        for (t in Tab.entries) {
            val b = TextView(host).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 2
                val h = if (ui.twoPane) Ui.MIN_TOUCH_DP else Ui.MIN_TOUCH_DP + 8
                minHeight = ui.dp(h)
                minimumHeight = ui.dp(h)
                setPadding(ui.dp(2), ui.dp(if (ui.twoPane) 2 else 6), ui.dp(2), ui.dp(if (ui.twoPane) 2 else 6))
                isClickable = true
                isFocusable = true
                contentDescription = t.label
                setOnClickListener { selectTab(t) }
            }
            tabButtons[t] = b
            bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        styleTabs()
        return bar
    }

    private fun styleTabs() {
        for ((t, b) in tabButtons) {
            val on = t == tab
            // альбомна — значок поруч з підписом (низький екран), книжкова — над ним
            b.text = ui.iconText(t.icon, t.label, if (on) Palette.accent else Palette.muted,
                sizeDp = if (ui.twoPane) LineIcon.LABEL_DP else LineIcon.BUTTON_DP, above = !ui.twoPane)
            b.setTextColor(if (on) Palette.text else Palette.muted)
            b.typeface = if (on) Ui.MEDIUM else Ui.REGULAR
            b.background = ui.pressable(
                if (on) ui.rounded(Palette.accentFill, Palette.accent, radiusDp = 12) else ui.rounded(Palette.transparent, radiusDp = 12),
                12,
            )
            b.isSelected = on
        }
    }

    private fun selectTab(t: Tab) {
        if (t == tab) {
            if (t == Tab.CHARTS && pointsFor != range) {
                render()
                loadHistory()
            }
            scroll.smoothScrollTo(0, 0)
            return
        }
        tab = t
        styleTabs()
        render(keepScroll = false)
        scroll.scrollTo(0, 0)
        if (t == Tab.CHARTS) {
            loadHistory()
            loadInsights()
        }
        if (t == Tab.CELLS) loadHour()
        if (t == Tab.MORE) historyBytes = history.size(address)
    }

    // ---------- малювання ----------

    /** [keepScroll] — оновлення того самого екрана (нові числа кожні 2 с, нові точки графіків): не стрибати вгору. */
    private fun render(keepScroll: Boolean = true) {
        val y = scroll.scrollY
        renderHead()
        body.removeAllViews()
        val s = snapshot ?: stale?.first ?: BatterySnapshot.EMPTY
        // T25: картки «Зв'язок» у вмісті більше нема — стан зв'язку живе в рядку сталої висоти вгорі (renderHead).
        when (tab) {
            Tab.NOW -> renderNow(s)
            Tab.CELLS -> renderCells(s)
            Tab.CHARTS -> renderCharts()
            Tab.BOARD -> renderBoard(s)
            Tab.MORE -> renderMore()
        }
        body.addView(ui.muted(host.versionLine, 12f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        if (keepScroll && y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun stamp(ms: Long) = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))

    /** Альбомна: дві колонки карток; книжкова — одна. */
    private fun columns(left: List<View>, right: List<View>) {
        if (ui.twoPane) {
            val l = ui.column(14)
            val r = ui.column(14)
            left.forEach { l.addView(it, ui.matchWrap()) }
            right.forEach { r.addView(it, ui.matchWrap()) }
            body.addView(ui.row(16).apply {
                gravity = Gravity.TOP
                addView(l, ui.weight(1f))
                addView(r, ui.weight(1f))
            }, ui.matchWrap())
        } else {
            (left + right).forEach { body.addView(it, ui.matchWrap()) }
        }
    }

    private fun renderHead() {
        head.removeAllViews()
        head.addView(ui.title(title, 21f))
        (linkStrip.parent as? ViewGroup)?.removeView(linkStrip)
        head.addView(linkStrip, ui.matchWrap())
        updateLinkStrip()
    }

    /**
     * T25 (відео faric 19.10 22:12): статус зв'язку — **два рядки по одному рядку тексту** у рамці сталої висоти.
     * Слова довші за ширину обрізаються «…» (повністю — у вікні за торканням), кнопка «Ще раз» лише ховається
     * (INVISIBLE, місце лишається), тож на «підключаюсь / читаю / втрачено / на зв'язку» спідометр під рядком не зсувається.
     */
    private fun buildLinkStrip(): View {
        val lines = ui.column(6)
        val top = ui.row(8).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(linkPill)
        top.addView(linkDetail, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui.text(INFO, 16f, Palette.accent))
        lines.addView(top, ui.matchWrap())
        lines.addView(linkSecond, ui.matchWrap())
        return ui.row(10).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8))
            minimumHeight = ui.dp(LINK_STRIP_DP)
            addView(lines, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(linkRetry)
            isClickable = true
            isFocusable = true
            contentDescription = tr("Зв'язок: що відбувається", "Connection: what is happening")
            setOnClickListener { showLinkInfo() }
        }
    }

    /**
     * T31: рядок картки відключення «ліва … права» в один рядок. Частина [keepLeft] (або права, якщо false) — важлива:
     * не стискається й не обрізається (відсоток «99 % → 62 %», тривалість). Друга бере, скільки лишилось: спершу менший
     * шрифт (`TextFit`, не дрібніше 10 dp), далі «…».
     */
    private fun outageRow(left: String, right: String, keepLeft: Boolean, color: Int, valueColor: Int, sizeSp: Float = 12.5f): View =
        ui.row(8).apply {
            gravity = Gravity.CENTER_VERTICAL
            val l = numbers(oneLine(ui.text(Ui.keepUnits(left), sizeSp, color, medium = !keepLeft)))
            val r = numbers(oneLine(ui.text(Ui.keepUnits(right), sizeSp, valueColor, medium = true))).apply { gravity = Gravity.END }
            val squeeze = if (keepLeft) r else l
            addView(l, if (keepLeft) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(r, if (keepLeft) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            squeeze.addOnLayoutChangeListener { v, x0, _, x1, _, _, _, _, _ ->
                val tv = v as TextView
                val avail = (x1 - x0 - tv.paddingLeft - tv.paddingRight).toFloat()
                val size = TextFit.size(tv.textSize, tv.paint.measureText(tv.text.toString()), avail, ui.dp(OUTAGE_MIN_DP).toFloat())
                if (size < tv.textSize - 0.5f) tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) }
            }
        }

    private fun oneLine(tv: TextView): TextView = tv.apply {
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun linkModel(): LinkLine.Model {
        val now = System.currentTimeMillis()
        return LinkLine.build(
            state = LinkLine.State.valueOf(status.phase.name),
            attempt = status.attempt,
            ofAttempts = status.ofAttempts,
            message = status.message,
            rssi = if (demo == null) BleScanner.lastRssi[address] else null,
            liveAt = if (snapshot != null) snapshotAt else 0L,
            liveStamp = if (snapshot != null) stamp(snapshotAt) else "",
            savedAt = stale?.second ?: 0L,
            savedStamp = stale?.second?.let { stamp(it) } ?: "",
            now = now,
            demo = demo != null,
        )
    }

    private fun updateLinkStrip() {
        val m = linkModel()
        val t = when (m.tone) {
            LinkLine.Tone.GOOD -> Ui.Tone.GOOD
            LinkLine.Tone.INFO -> Ui.Tone.INFO
            LinkLine.Tone.WARN -> Ui.Tone.WARN
            LinkLine.Tone.BAD -> Ui.Tone.BAD
            LinkLine.Tone.NEUTRAL -> Ui.Tone.NEUTRAL
        }
        if (linkPill.text.toString() != m.pill) linkPill.text = m.pill
        linkPill.setTextColor(t.color)
        linkPill.background = ui.rounded(t.fill, radiusDp = 20)
        if (linkDetail.text.toString() != m.detail) linkDetail.text = m.detail
        if (linkSecond.text.toString() != m.second) linkSecond.text = m.second
        linkSecond.setTextColor(if (snapshot == null && status.phase != LinkStatus.Phase.LIVE) Palette.warning else Palette.muted)
        linkRetry.visibility = if (m.retry) View.VISIBLE else View.INVISIBLE
        val (fill, stroke) = when (m.tone) {
            LinkLine.Tone.BAD -> Palette.dangerFill to Palette.danger
            LinkLine.Tone.WARN -> Palette.warningFill to Palette.warning
            else -> Palette.surface to Palette.border
        }
        linkStrip.background = ui.pressable(ui.rounded(fill, stroke, radiusDp = 14), 14)
    }

    /** Повне пояснення зв'язку — те, що раніше стояло в картці й штовхало екран (T25). */
    private fun showLinkInfo() {
        val m = linkModel()
        val box = ui.column(10)
        box.addView(ui.pill(m.pill, Ui.Tone.NEUTRAL).apply { setTextColor(Palette.text) })
        val msg = status.message.ifBlank {
            when (status.phase) {
                LinkStatus.Phase.CONNECTING -> tr("Підключаюсь до батареї. Зазвичай 2–5 секунд, якщо вона поруч (1–3 м).",
                        "Connecting to the battery. Usually 2–5 seconds if it is near (1–3 m).")
                LinkStatus.Phase.READING -> tr("Підключились, читаю дані плати…", "Connected, reading BMS data…")
                LinkStatus.Phase.LIVE -> tr("Дані приходять наживо, раз на 2 секунди.", "Live data, every 2 seconds.")
                else -> tr("Натисни «Підключити ще раз».", "Tap \"Reconnect\".")
            }
        }
        box.addView(ui.text(msg, 15f, Palette.text))
        box.addView(ui.muted(m.second.replaceFirstChar { it.uppercase() } + ".", 14f))
        box.addView(ui.muted(Glossary.get("onelink")?.what ?: "", 14f))
        val dlg = host.overlay.dialog(
            tr("Зв'язок", "Connection"), "",
            actions = listOf(
                tr("Підключити ще раз", "Reconnect") to Ui.Kind.PRIMARY,
                tr("Журнал зв'язку", "Connection log") to Ui.Kind.NORMAL,
                tr("Закрити", "Close") to Ui.Kind.QUIET,
            ),
            onAction = { i ->
                host.overlay.dismiss()
                when (i) {
                    0 -> reconnect()
                    1 -> showLog()
                }
            },
            extra = box,
        )
        host.overlay.show(DLG_LINK, dlg)
    }

    // ---------- «Зараз» ----------

    private fun renderNow(s: BatterySnapshot) {
        val gaugeCard = ui.card(6)
        gaugeCard.addView(labelRow(tr("Заряд", "Charge"), LineIcon.BATTERY, "soc"))
        gaugeCard.addView(GaugeView(host).apply {
            soc = s.soc
            current = s.current
            power = s.power
            voltage = s.voltage
            showAmps = marks.gaugeAmps
            caption = Fmt.num(s.remainAh, 1) + tr(" з ", " of ") + Fmt.num(s.fullAh, 1) + " " + Fmt.AH
            noData = data == Data.NONE
            setOnClickListener { showInfo("zones") }
        }, ui.matchWrap())
        // Перемикач великого числа: вати чи ампери (faric 19.10 03:02: «щоб можна було і те дивитися, і те»).
        gaugeCard.addView(ui.grid(listOf(
            ui.choice(tr("Вати", "Watts"), !marks.gaugeAmps) { if (marks.gaugeAmps) { marks.gaugeAmps = false; render() } },
            ui.choice(tr("Ампери", "Amps"), marks.gaugeAmps) { if (!marks.gaugeAmps) { marks.gaugeAmps = true; render() } },
        )), ui.matchWrap())
        if (data == Data.NONE) {
            gaugeCard.addView(ui.text(tr("Немає даних", "No data"), 15f, Palette.muted, medium = true).apply { gravity = Gravity.CENTER },
                    ui.matchWrap())
            gaugeCard.addView(ui.muted(tr("Зони на ободі: червона 0–10 %, помаранчева 10–20 %, зелена 20–90 %, помаранчева 90–100 %. Торкнись — докладно.",
                "Rim zones: red 0–10 %, orange 10–20 %, green 20–90 %, orange 90–100 %. Tap for details."), 13f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        } else {
            val zone = Zones.of(s.soc)
            gaugeCard.addView(ui.text(zone.title, 15f, GaugeView.toneColor(zone.tone), medium = true).apply { gravity = Gravity.CENTER }, ui.matchWrap())
            gaugeCard.addView(ui.muted(zone.advice, 13f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        }

        val now = ui.card(8)
        now.addView(labelRow(tr("Зараз", "Now"), LineIcon.PULSE, null))
        now.addView(fact(tr("Напруга", "Voltage"), Fmt.volts(s.voltage), "voltage"))
        // Стрілки як на спідометрі: віддає — ▲ червоне (енергія йде з батареї), заряджається — ▼ зелене.
        val (curText, curColor) = when (s.flow) {
            BatterySnapshot.Flow.CHARGING -> Fmt.amps(s.current) + tr(" ▼ бере", " ▼ in") to Palette.success
            BatterySnapshot.Flow.DISCHARGING -> Fmt.amps(-s.current) + tr(" ▲ віддає", " ▲ out") to Palette.danger
            BatterySnapshot.Flow.IDLE -> Fmt.amps(s.current) to Palette.text
        }
        now.addView(fact(tr("Струм", "Current"), curText, "current", curColor))
        now.addView(fact(tr("Потужність", "Power"), Fmt.watts(kotlin.math.abs(s.power)), "power", curColor))
        if (s.temperatures.isNotEmpty()) {
            val hot = s.temperatures.max()
            val tName = if (s.temperatures.size == 1) tr("Температура", "Temperature") else tr("Температура (макс.)", "Temperature (max)")
            now.addView(fact(tName, Fmt.celsius(hot), "temp", tempColor(hot)))
        } else if (data == Data.NONE) {
            now.addView(fact(tr("Температура", "Temperature"), DASH, "temp"))
        }
        s.deltaMv?.let { d -> now.addView(fact(tr("Різниця комірок", "Cell difference"), "${Fmt.millivolts(d)} — ${s.deltaVerdict!!.title}",
                "delta", verdictColor(s))) }
            ?: if (data == Data.NONE) now.addView(fact(tr("Різниця комірок", "Cell difference"), DASH, "delta")) else Unit
        now.addView(fact(tr("Цикли", "Cycles"), s.cycles.toString(), "cycles"))

        val state = ui.card()
        state.addView(labelRow(tr("Стан плати", "BMS state"), LineIcon.SHIELD, "protState"))
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        if (data == Data.NONE) {
            row.addView(pillInfo(tr("Заряд: $DASH", "Charge: $DASH"), Ui.Tone.NEUTRAL, "chargeSw"))
            row.addView(pillInfo(tr("Розряд: $DASH", "Discharge: $DASH"), Ui.Tone.NEUTRAL, "dischargeSw"))
            row.addView(pillInfo(tr("Балансування: $DASH", "Balancing: $DASH"), Ui.Tone.NEUTRAL, "balance"))
            row.addView(pillInfo(tr("Захист: $DASH", "Protection: $DASH"), Ui.Tone.NEUTRAL, "protState"))
            state.addView(row, ui.matchWrap())
            columns(listOf(gaugeCard), listOf(now, state))
            return
        }
        row.addView(pillInfo(if (s.chargeOn) tr("Заряд: дозволено", "Charge: allowed") else tr("Заряд: вимкнено", "Charge: off"),
                if (s.chargeOn) Ui.Tone.GOOD else Ui.Tone.BAD, "chargeSw"))
        row.addView(pillInfo(if (s.dischargeOn) tr("Розряд: дозволено", "Discharge: allowed") else tr("Розряд: вимкнено", "Discharge: off"),
                if (s.dischargeOn) Ui.Tone.GOOD else Ui.Tone.BAD, "dischargeSw"))
        row.addView(
            if (s.balancingCells.isEmpty()) pillInfo(tr("Балансування: ні", "Balancing: no"), Ui.Tone.NEUTRAL, "balance")
            else pillInfo(tr("Балансування: ", "Balancing: ") + s.balancingCells.joinToString(", ") { cellNo(it) }, Ui.Tone.INFO,
                    "balance"),
        )
        row.addView(pillInfo(if (s.hasProblem) tr("Захист спрацював", "Protection triggered") else tr("Захист: усе гаразд", "Protection: all OK"),
                if (s.hasProblem) Ui.Tone.BAD else Ui.Tone.GOOD, "protState"))
        state.addView(row, ui.matchWrap())

        val left = mutableListOf<View>(gaugeCard)
        timeCard(s)?.let { left += it }
        val right = mutableListOf<View>()
        if (s.hasProblem) right += problemCard(s)
        right += now
        right += state
        columns(left, right)
    }

    /**
     * «Вистачить на» (розряд) або «До повної» (заряд) — faric 19.10 03:02 / 03:31. Струм — середній ([steady]),
     * число не скаче. Пороги 20 / 10 / 5 %; при заряді — без «до 90 %» (інвертор однаково заряджає, поки плата не зупинить),
     * натомість «за 1 / 2 год такого заряду +N %» — щоб вирішити, скільки ганяти генератор.
     */
    private fun timeCard(s: BatterySnapshot): View? {
        if (data == Data.NONE) return null
        val amps = if (data == Data.LIVE) steady.shown ?: s.current else s.current
        val card = ui.card(8)
        TimeLeft.discharge(s.remainAh, s.fullAh, amps)?.let { d ->
            card.addView(labelRow(tr("Вистачить на", "Lasts for"), LineIcon.BATTERY, "time"))
            card.addView(numbers(ui.text("≈ " + Fmt.duration(d.totalH), 26f, Palette.text, medium = true)))
            val to20 = d.toThreshold.first { it.first == 20 }.second
            card.addView(ui.text(
                if (to20 != null) tr("(до 20 % — ${Fmt.duration(to20)})", "(to 20 % — ${Fmt.duration(to20)})")
                else tr("(уже нижче 20 % — час заряджати)", "(already below 20 % — time to charge)"),
                15f, Palette.danger, medium = true,
            ))
            card.addView(socStrip(s.soc, charging = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)))
            val steps = d.toThreshold.map { (p, h) ->
                val (fill, color, note) = when (p) {
                    20 -> Triple(Palette.selectedFill, Palette.success, tr("спокійно", "no rush"))
                    10 -> Triple(Palette.warningFill, Palette.warning, tr("час заряджати", "charge now"))
                    else -> Triple(Palette.dangerFill, Palette.danger, tr("не варто нижче", "don't go lower"))
                }
                step(tr("до $p %", "to $p %"), h?.let { Fmt.duration(it) } ?: tr("уже нижче", "already below"), note, fill, color)
            }
            card.addView(ui.grid(steps, perRow = 3, gapDp = 6), ui.matchWrap())
            card.addView(ui.muted(tr("Рахую з залишку (${Fmt.ampHours(s.remainAh)}) і середнього струму ${Fmt.amps(-amps)} за 2 хв. " +
                "Число перераховується, лише коли струм стабільно змінився більше ніж на 10 %.",
                "Based on remaining (${Fmt.ampHours(s.remainAh)}) and average current ${Fmt.amps(-amps)} over 2 min. " +
                "The number updates only when the current changes steadily by more than 10 %."), 12.5f))
            return card
        }
        TimeLeft.charge(s.remainAh, s.fullAh, amps)?.let { c ->
            card.addView(labelRow(tr("До повної", "Until full"), LineIcon.BATTERY, "time"))
            card.addView(numbers(ui.text(if (c.toFullH <= 0.0) tr("уже повна", "already full") else "≈ " + Fmt.duration(c.toFullH), 26f,
                    Palette.text, medium = true)))
            card.addView(ui.text(tr("(до 100 % — заряд зупинить сама плата)",
                    "(to 100 % — the BMS stops charging itself)"), 15f, Palette.success, medium = true))
            card.addView(socStrip(s.soc, charging = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)))
            val perH = c.percentPerHour
            card.addView(ui.grid(listOf(
                step(tr("за 1 год такого заряду", "in 1 h at this rate"), "+${Fmt.num(minOf(perH, 100.0 - s.soc).coerceAtLeast(0.0), 0)} %",
                    "≈ ${Fmt.num(amps, 1)} ${Fmt.AH}", Palette.selectedFill, Palette.success),
                step(tr("за 2 год", "in 2 h"), "+${Fmt.num(minOf(perH * 2, 100.0 - s.soc).coerceAtLeast(0.0), 0)} %",
                    "≈ ${Fmt.num(amps * 2, 1)} ${Fmt.AH}", Palette.selectedFill, Palette.success),
            ), perRow = 2, gapDp = 6), ui.matchWrap())
            card.addView(ui.muted(tr("Зручно для генератора: видно, скільки дасть кожна година. Наприкінці заряду струм сам спадає — час уточниться.",
                "Handy for a generator: you see what each hour gives. Near the end of charge the current drops — the time will adjust."), 12.5f))
            return card
        }
        return null
    }

    /** Смужка «де заряд»: при розряді — зелене до 20 %, жовте 10–20, червоне 5–10; при заряді — є / бракує до повної. */
    private fun socStrip(soc: Int, charging: Boolean): View {
        val row = ui.row(2)
        fun seg(w: Float, color: Int) = View(host).apply { background = ui.rounded(color, radiusDp = 4) }.also {
            if (w > 0) row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w))
        }
        if (charging) {
            seg(soc.toFloat(), Palette.accent)
            seg((100 - soc).toFloat(), Palette.success)
        } else {
            seg((soc - 20).coerceAtLeast(0).toFloat(), Palette.success)
            seg((minOf(soc, 20) - 10).coerceAtLeast(0).toFloat(), Palette.warning)
            seg((minOf(soc, 10) - 5).coerceAtLeast(0).toFloat(), Palette.danger)
            seg(minOf(soc, 5).toFloat(), Palette.dangerFill)
        }
        return row
    }

    private fun step(top: String, value: String, note: String, fill: Int, color: Int): View = ui.column(2).apply {
        background = ui.rounded(fill, radiusDp = 10)
        setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        addView(ui.text(top, 12f, color))
        addView(numbers(ui.text(Ui.keepUnits(value), 14.5f, color, medium = true)))
        addView(ui.text(note, 12f, color))
        isClickable = true
        setOnClickListener { showInfo("time") }
    }

    private fun numbers(t: TextView): TextView = ui.numbers(t)

    /** Номер комірки: «№3» / «#3». */
    private fun cellNo(n: Int) = tr("№$n", "#$n")

    // ---------- «Комірки» ----------

    private fun renderCells(s: BatterySnapshot) {
        val cards = ArrayList<View>()
        if (s.cellsMv.isEmpty()) {
            val card = ui.card(8)
            card.addView(labelRow(tr("Комірки", "Cells"), LineIcon.CELLS, "cells"))
            card.addView(ui.muted(tr("Напруга кожної комірки з'явиться після підключення. У батареї Aolithium їх 4.",
                    "Each cell voltage appears after connecting. The Aolithium battery has 4."), 14f))
            for (i in 1..4) card.addView(fact(tr("Комірка $i", "Cell $i"), DASH, "cells"))
            card.addView(fact(tr("Різниця", "Difference"), DASH, "delta"))
            cards += card
        } else {
            val card = ui.card(8)
            card.addView(labelRow(tr("Комірки: ${s.cellsMv.size}", "Cells: ${s.cellsMv.size}"), LineIcon.CELLS, "cellTrend"))
            if (data == Data.LIVE && s.flow != BatterySnapshot.Flow.IDLE) {
                val pill = if (s.flow == BatterySnapshot.Flow.CHARGING) ui.pill(tr("▼ Заряджається · ${Fmt.amps(s.current)}",
                        "▼ Charging · ${Fmt.amps(s.current)}"), Ui.Tone.GOOD)
                else ui.pill(tr("▲ Віддає · ${Fmt.amps(-s.current)}", "▲ Discharging · ${Fmt.amps(-s.current)}"), Ui.Tone.BAD)
                card.addView(FlowLayout(host, ui.dp(8), ui.dp(6)).apply { addView(pill) }, ui.matchWrap())
                card.addView(ui.muted(tr("Комірки з'єднані ланцюжком — струм через усі однаковий. А напруга росте по-різному: дивись «за 1 хв».",
                    "Cells are in series — the same current flows through all. But voltage grows differently: see \"per min\"."), 13f))
            }
            val dev = CellTrend.fromAverage(s.cellsMv)
            val trend = if (data == Data.LIVE) CellTrend.perMinute(cellSamples.toList()) else null
            val (hi, lo) = CellTrend.marks(s.cellsMv)
            // Якщо хоч одна вирівнюється — місце під мітку є в кожній плитці, щоб усі були однієї висоти.
            val anyBalancing = s.cellsMv.indices.any { (it + 1) in s.balancingCells }
            val tiles = s.cellsMv.mapIndexed { i, mv ->
                cellTile(i, mv, dev[i], trend?.getOrNull(i), isHi = i in hi, isLo = i in lo, balancing = (i + 1) in s.balancingCells, anyBalancing = anyBalancing)
            }
            card.addView(ui.grid(tiles, perRow = if (ui.twoPane && tiles.size > 2) 4 else 2), ui.matchWrap())
            s.deltaMv?.let { d -> card.addView(fact(tr("Різниця", "Difference"), "${Fmt.millivolts(d)} — ${s.deltaVerdict!!.title}",
                    "delta", verdictColor(s))) }
            s.averageCellV?.let { card.addView(fact(tr("Середня", "Average"), Fmt.volts(it, 3), "avgcell")) }
            card.addView(ui.muted(tr("Рамка: блакитна — найвища, помаранчева — найнижча; фіолетова мітка — вирівнюється. " +
                "За 1 хв: ▲ зелене — напруга росте, ▼ червоне — падає. Смужка — від 2,5 В (порожня) до 3,65 В (повна).",
                "Frame: blue — highest, orange — lowest; violet label — balancing. " +
                "Per 1 min: ▲ green — voltage rises, ▼ red — falls. Bar — from 2.5 V (empty) to 3.65 V (full)."), 12f))
            cards += card

            val bal = ui.card(8)
            val jk = s.jk
            if (jk != null) {
                // v0.4: активний балансир JK — каже струм і напрям (з найвищої в найнижчу), прев'ю екран 3.
                bal.addView(labelRow(tr("Балансир JK", "JK balancer"), LineIcon.CELLS, "jkbalance"))
                if (jk.balancing && s.balancingCells.size == 2) {
                    bal.addView(ui.text(tr("Переливає: з №${s.balancingCells[0]} у №${s.balancingCells[1]}",
                            "Transfers: from #${s.balancingCells[0]} to #${s.balancingCells[1]}"), 15f, Palette.violet, medium = true))
                    bal.addView(fact(tr("Струм балансира", "Balancer current"), Fmt.amps(kotlin.math.abs(jk.balanceCurrent)), "jkbalance"))
                    bal.addView(ui.muted(if (jk.balancerAction == 2) tr("Вирівнює під час розряду.", "Balancing during discharge.")
                        else tr("Вирівнює під час заряду.", "Balancing during charge."), 13.5f))
                } else {
                    bal.addView(ui.text(tr("Зараз не переливає", "Not transferring now"), 15f, Palette.text, medium = true))
                    s.deltaMv?.let { bal.addView(ui.muted(tr("Різниця ${Fmt.millivolts(it)}. Балансир вмикається сам, коли комірки розійдуться більше за поріг у налаштуваннях плати.",
                        "Difference ${Fmt.millivolts(it)}. The balancer turns on by itself when cells drift apart more than the BMS setting allows."), 13.5f)) }
                }
                bal.addView(ui.muted(tr("Активний: бере енергію з найвищої комірки й віддає найнижчій — без нагріву. На плитках — фіолетова мітка «вирівнюється».",
                    "Active: takes energy from the highest cell and gives it to the lowest — no heat. Tiles show a violet \"balancing\" label."), 12.5f))
                cards += bal
            } else {
            bal.addView(labelRow(tr("Як плата вирівнює комірки", "How the BMS balances cells"), LineIcon.CELLS, "balance"))
            if (s.balancingCells.isEmpty()) {
                bal.addView(ui.text(tr("Зараз не вирівнює", "Not balancing now"), 15f, Palette.text, medium = true))
                bal.addView(ui.muted(tr("Плата Aolithium вмикає вирівнювання наприкінці заряду, коли комірки розходяться.",
                        "The Aolithium BMS balances at the end of charge, when cells drift apart."), 13.5f))
            } else {
                bal.addView(ui.text(tr("Зараз вирівнює: ", "Balancing now: ") + s.balancingCells.joinToString(", ") { cellNo(it) }, 15f,
                        Palette.violet, medium = true))
                bal.addView(ui.muted(tr("Плата злегка розряджає цю комірку (у тепло, через резистор), поки інші її доганяють.",
                        "The BMS slightly drains this cell (into heat, via a resistor) while others catch up."), 13.5f))
            }
            bal.addView(ui.muted(tr("Це пасивне вирівнювання: скільки ампер — плата JBD не повідомляє, лише «вирівнює / ні». " +
                "У Logic Power — активний балансир: перекачує енергію з найвищої комірки в найнижчу і показує струм.",
                "This is passive balancing: the JBD BMS does not report amps, only \"balancing / no\". " +
                "Logic Power has an active balancer: it moves energy from the highest cell to the lowest and shows the current."), 12.5f))
            cards += bal
            }

            // v0.4: опір дроту кожної комірки (лише JK), прев'ю екран 4.
            s.jk?.takeIf { it.wireMohm.isNotEmpty() }?.let { j ->
                val wire = ui.card(8)
                wire.addView(labelRow(tr("Опір дротів комірок", "Cell wire resistance"), LineIcon.CELLS, "wire"))
                val typical = j.wireMohm.sorted()[j.wireMohm.size / 2]
                j.wireMohm.forEachIndexed { i, r ->
                    val odd = typical > 0 && r > typical * 3 / 2
                    wire.addView(fact(tr("Комірка ${i + 1}", "Cell ${i + 1}"), "$r ${Fmt.MOHM}", "wire",
                            if (odd) Palette.warning else Palette.text))
                }
                wire.addView(ui.muted(tr("Мають бути схожі. Помітно більший — підтягни чи почисти контакт цієї комірки.",
                        "They should be similar. A much higher one — tighten or clean that cell's contact."), 12.5f))
                cards += wire
            }

            val hour = ui.card(8)
            hour.addView(labelRow(tr("Різниця комірок за 1 год", "Cell difference, last 1 h"), LineIcon.CHART, "delta"))
            val hp = hourPts
            if (hp.isNullOrEmpty()) {
                hour.addView(ui.muted(if (hp == null) tr("Завантажую…", "Loading…") else tr("Ще немає записів за останню годину.",
                        "No records for the last hour yet."), 13f))
            } else {
                val to = System.currentTimeMillis()
                val from = to - 3_600_000L
                val chart = ChartView(host).apply {
                    set(listOf(ChartView.Series(tr("Різниця", "Difference"), Palette.accent,
                            HistoryMath.bucket(hp, from, to, 120) { it.deltaMv?.toDouble() })),
                        from, to, Fmt.MV, 0, min = 0.0, gapMs = HistoryMath.GAP_MS)
                }
                hour.addView(chart, ui.matchWrap())
                val first = hp.firstNotNullOfOrNull { it.deltaMv }
                val last = hp.lastOrNull()?.deltaMv
                if (first != null && last != null) hour.addView(ui.muted(tr("Годину тому ${Fmt.millivolts(first)}, зараз ${Fmt.millivolts(last)}. " +
                    "Наприкінці заряду різниця трохи росте — це нормально. До 30 мВ — рівно.",
                    "An hour ago ${Fmt.millivolts(first)}, now ${Fmt.millivolts(last)}. " +
                    "Near the end of charge the difference grows a bit — that is normal. Up to 30 mV is even."), 12.5f))
            }
            cards += hour
        }
        val temps = ui.card(8)
        temps.addView(labelRow(tr("Температура", "Temperature"), LineIcon.THERMO, "temp"))
        if (s.temperatures.isEmpty()) temps.addView(
            if (data == Data.NONE) fact(tr("Датчики", "Sensors"), DASH, "temp") else ui.muted(tr("Плата не повідомила жодного датчика.",
                    "The BMS reported no sensors."), 14f),
        )
        s.temperatures.forEachIndexed { i, t ->
            temps.addView(fact(tr("Датчик ${i + 1}", "Sensor ${i + 1}"), Fmt.celsius(t), "temp", tempColor(t)))
        }
        s.jk?.let { j ->
            j.mosTemp?.let { temps.addView(fact(tr("Ключі плати (MOS)", "BMS switches (MOS)"), Fmt.celsius(it), "mos",
                    if (it >= 70) Palette.warning else Palette.text)) }
            val heat = if (j.heatingOn) tr("гріє · ", "heating · ") + Fmt.amps(j.heatingCurrent) else tr("вимкнено", "off")
            temps.addView(fact(tr("Підігрів", "Heater"), heat, "heating"))
        }
        temps.addView(ui.muted(tr("Найкраще 15–35 °C. Нижче 0 °C плата не дасть заряджати.",
                "Best 15–35 °C. Below 0 °C the BMS blocks charging."), 12.5f))
        columns(cards, listOf(temps))
    }

    /**
     * Плитка комірки (прев'ю «комірки як у JK», faric 19.10 02:44).
     * T19 (відео 19.10 13:57 — плитки «пливли»): кожна плитка однієї висоти, що б не показувала — рядок «№ + мітка»
     * завжди однієї висоти й в один рядок, «від середньої» — завжди два рядки, числа праворуч — однакової ширини.
     */
    private fun cellTile(i: Int, mv: Int, dev: Int, perMin: Int?, isHi: Boolean, isLo: Boolean, balancing: Boolean, anyBalancing: Boolean): View {
        val stroke = when {
            isHi -> Palette.accent
            isLo -> Palette.warning
            else -> Palette.border
        }
        val tile = ui.column(4).apply {
            background = ui.pressable(ui.rounded(Palette.surfaceAlt, stroke, radiusDp = 12, strokeDp = if (isHi || isLo) 2 else 1), 12)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { showInfo("cellTrend") }
        }
        val head = ui.row(6).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(HEAD_DP)
        }
        head.addView(ui.text(cellNo(i + 1), 13f, Palette.muted, medium = true).apply { maxLines = 1 })
        val mark = when {
            isHi -> ui.pill(tr("найвища", "highest"), Ui.Tone.INFO)
            isLo -> ui.pill(tr("найнижча", "lowest"), Ui.Tone.WARN)
            else -> null
        }
        mark?.apply {
            textSize = 11.5f
            setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(3))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            head.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        tile.addView(head, ui.matchWrap())
        tile.addView(numbers(ui.text(Ui.keepUnits(Fmt.cellVolts(mv)), 21f, Palette.text, medium = true)))
        val k = ((mv - CellBarsView.LOW_MV).toFloat() / (CellBarsView.HIGH_MV - CellBarsView.LOW_MV)).coerceIn(0.03f, 1f)
        val bar = ui.row().apply { background = ui.rounded(Palette.track, radiusDp = 4) }
        bar.addView(View(host).apply {
            background = ui.rounded(when {
                isHi -> Palette.accent
                isLo -> Palette.warning
                else -> Palette.muted
            }, radiusDp = 4)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, k))
        bar.addView(View(host), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - k))
        tile.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)))
        tile.addView(miniRow(tr("від\nсередньої", "from\naverage"),
                (if (dev > 0) "+" else if (dev < 0) "−" else "") + "${kotlin.math.abs(dev)} ${Fmt.MV}", Palette.text, lines = 2))
        val shownPerMin = perMin?.let { CellTrend.shown(it) }
        val (t, c) = when {
            shownPerMin == null -> (if (data == Data.LIVE) tr("рахую…", "counting…") else "—") to Palette.muted
            shownPerMin > 0 -> "▲ +$shownPerMin ${Fmt.MV}" to Palette.success
            shownPerMin < 0 -> "▼ −${-shownPerMin} ${Fmt.MV}" to Palette.danger
            else -> tr("без змін", "no change") to Palette.muted
        }
        tile.addView(miniRow(tr("за 1 хв", "per min"), t, c))
        if (anyBalancing) {
            val pill = ui.pill(tr("≈ вирівнюється", "≈ balancing"), Ui.Tone.VIOLET).apply {
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Не вирівнюється — місце лишається (невидиме), щоб плитки в ряду були однієї висоти.
                visibility = if (balancing) View.VISIBLE else View.INVISIBLE
            }
            tile.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return tile
    }

    /**
     * «назва … значення». Назва — рівно [lines] рядків (переноси задані в тексті, не залежать від числа),
     * значення — завжди однієї ширини (найширше можливе «▼ −88 мВ»), тож нічого не переливається.
     */
    private fun miniRow(name: String, value: String, color: Int, lines: Int = 1): View = ui.row(6).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(name, 12f, Palette.muted).apply {
            maxLines = lines
            minLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            // Найдовше слово не влазить (шрифт 1.3, вузький телефон) — трохи зменшуємо шрифт, а не обрізаємо
            // «середнь…» (знімок CI 19.10). Усі плитки однієї ширини — тож і шрифт однаковий у всіх.
            // Розмір запам'ятовуємо: плитки перебудовуються кожні 2 с — без цього шрифт щоразу «стрибав» би 12 → менше.
            if (miniCaptionPx > 0f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, miniCaptionPx)
            val longest = name.split('\n', ' ').maxBy { it.length }
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val tv = v as TextView
                val avail = (r - l - tv.paddingLeft - tv.paddingRight).toFloat()
                val need = tv.paint.measureText(longest)
                if (avail > 0 && need > avail && tv.textSize > ui.dp(9)) {
                    val size = maxOf(ui.dp(9).toFloat(), tv.textSize * avail / need * 0.98f)
                    miniCaptionPx = if (miniCaptionPx > 0f) minOf(miniCaptionPx, size) else size
                    tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, miniCaptionPx) }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = numbers(ui.text(Ui.keepUnits(value), 12.5f, color, medium = true))
        v.maxLines = 1
        v.gravity = Gravity.END
        // Найширші можливі значення — у поточній мові (одиниця й слова змінюються разом з мовою).
        val widest = listOf(Ui.keepUnits("▼ −20 ${Fmt.MV}"), tr("без змін", "no change"), tr("рахую…", "counting…"))
        v.minWidth = kotlin.math.ceil(widest.maxOf { v.paint.measureText(it) }).toInt()
        addView(v)
    }

    private fun loadHour() {
        val now = System.currentTimeMillis()
        hourLoadedAt = now
        history.load(address, now - 3_600_000L, now) { pts ->
            hourPts = pts
            if (tab == Tab.CELLS) render()
        }
    }

    private fun loadInsights() {
        val now = System.currentTimeMillis()
        insightLoadedAt = now
        history.load(address, now - INSIGHT_DAYS * DailyMin.DAY_MS, now) { pts ->
            insightPts = pts
            if (tab == Tab.CHARTS) render()
        }
    }

    // ---------- «Графіки» ----------

    private fun loadHistory() {
        val r = range
        val now = System.currentTimeMillis()
        pointsLoadedAt = now
        history.load(address, now - r.ms, now) { pts ->
            if (r != range) return@load
            points = pts
            pointsFor = r
            if (tab == Tab.CHARTS) render()
        }
    }

    private fun renderCharts() {
        // Періоди — один рядок на всю ширину, 5 рівних кнопок.
        val chipList = ArrayList<View>()
        for (r in Range.entries) {
            chipList.add(ui.choice(r.label, r == range) {
                if (r != range) {
                    // Старі графіки лишаються, поки вантажаться нові: сторінка не стрибає (відео faric 19.10 01:38).
                    range = r
                    render()
                    loadHistory()
                }
            })
        }
        chipList.forEach { (it as TextView).apply { minWidth = 0; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); textSize = 14f } }
        body.addView(ui.grid(chipList, perRow = chipList.size, gapDp = 6), ui.matchWrap())
        val shown = pointsFor
        if (shown == null) {
            body.addView(ui.muted(tr("Завантажую історію…", "Loading history…"), 14f).apply { minHeight = ui.dp(ChartView.HEIGHT_DP) },
                    ui.matchWrap())
            return
        }
        val to = System.currentTimeMillis()
        val from = to - shown.ms
        val pts = points
        val stats = HistoryMath.stats(pts)

        val sum = ui.card(8)
        sum.addView(labelRow(if (shown != range) tr("За ${shown.label} · оновлюю…", "Last ${shown.label} · updating…")
            else tr("За ${shown.label}", "Last ${shown.label}"), LineIcon.LIST, "energy"))
        if (pts.isEmpty()) {
            sum.addView(ui.muted(tr("Ще немає записів. Історія пишеться раз на 10 с, поки застосунок відкритий і на зв'язку, " +
                "а з увімкненим «Запис у фоні» («Ще») — раз на ~5 хв і коли закритий.",
                "No records yet. History is saved every 10 s while the app is open and connected; " +
                "with \"Background recording\" (\"More\") on — every ~5 min, even when closed."), 14f))
        } else {
            sum.addView(fact(tr("Заряджено", "Charged"),
                    "${Fmt.num(stats.chargedWh, 0)} ${Fmt.WH} · ${Fmt.num(stats.chargedAh, 1)} ${Fmt.AH}", "energy", Palette.success,
                    known = true))
            sum.addView(fact(tr("Віддано", "Discharged"),
                    "${Fmt.num(stats.dischargedWh, 0)} ${Fmt.WH} · ${Fmt.num(stats.dischargedAh, 1)} ${Fmt.AH}", "energy", Palette.danger,
                    known = true))
            if (stats.socMin != null) sum.addView(fact(tr("Заряд", "Charge"), "${stats.socMin}–${stats.socMax} %", "soc", known = true))
            sum.addView(fact(tr("Найбільший струм", "Peak current"),
                tr("заряд ${Fmt.amps(stats.maxChargeA)} · розряд ${Fmt.amps(stats.maxDischargeA)}",
                    "charge ${Fmt.amps(stats.maxChargeA)} · discharge ${Fmt.amps(stats.maxDischargeA)}"), "current", known = true))
            if (stats.tempMin != null) sum.addView(fact(tr("Температура", "Temperature"),
                    "${Fmt.celsius(stats.tempMin)} … ${Fmt.celsius(stats.tempMax!!)}", "temp", known = true))
            stats.deltaMaxMv?.let { sum.addView(fact(tr("Найбільша різниця комірок", "Max cell difference"), Fmt.millivolts(it), "delta",
                    known = true)) }
            val share = (stats.coveredMs * 100 / shown.ms).coerceIn(0, 100)
            sum.addView(ui.muted(tr("Записано ${share} % часу (решта — застосунок був закритий і фон вимкнений).",
                    "Recorded ${share} % of the time (the rest — app closed, background off)."), 12.5f))
        }

        val n = 240
        val gap = maxOf(HistoryMath.GAP_MS, shown.ms / n * 3)
        fun ser(name: String, color: Int, f: (HistoryPoint) -> Double?) = ChartView.Series(name, color, HistoryMath.bucket(pts, from, to, n, f))
        fun chartCard(name: String, icon: LineIcon, info: String, chart: ChartView, legend: List<Pair<String, Int>> = emptyList()): View {
            val c = ui.card(8)
            c.addView(labelRow(name, icon, info))
            c.addView(chart, ui.matchWrap())
            if (legend.isNotEmpty()) {
                val lg = FlowLayout(host, ui.dp(12), ui.dp(4))
                legend.forEach { (t, col) -> lg.addView(ui.text("● $t", 12.5f, col)) }
                c.addView(lg, ui.matchWrap())
            }
            return c
        }
        val socChart = ChartView(host).apply { set(listOf(ser(tr("Заряд", "Charge"), Palette.accent) { it.soc.toDouble() }), from, to, "%",
                0, min = 0.0, max = 100.0, gapMs = gap) }
        val curChart = ChartView(host).apply { set(listOf(ser(tr("Струм", "Current"), Palette.success) { it.current }), from, to, Fmt.A, 2,
                zeroSplit = true, gapMs = gap) }
        val pwrChart = ChartView(host).apply { set(listOf(ser(tr("Потужність", "Power"), Palette.success) { it.power }), from, to, Fmt.W, 0,
                zeroSplit = true, gapMs = gap) }
        val vChart = ChartView(host).apply { set(listOf(ser(tr("Напруга", "Voltage"), Palette.accent) { it.voltage }), from, to, Fmt.V, 2,
                gapMs = gap) }
        val tChart = ChartView(host).apply {
            set(listOf(ser(tr("Макс.", "Max"), Palette.warning) { it.tempMax }, ser(tr("Мін.", "Min"), Palette.accent) { it.tempMin }),
                    from, to, "°C", 1, gapMs = gap)
        }
        val dChart = ChartView(host).apply { set(listOf(ser(tr("Різниця", "Difference"), Palette.warning) { it.deltaMv?.toDouble() }), from,
                to, Fmt.MV, 0, min = 0.0, gapMs = gap) }
        // Без зеленого й червоного: тут вони означають заряд / розряд.
        val cellColors = listOf(Palette.warning, Palette.accent, Palette.violet, Palette.text)
        val cellsN = pts.lastOrNull()?.cellsMv?.size ?: 0
        val cChart = ChartView(host).apply {
            set((0 until cellsN).map { i -> ser(cellNo(i + 1), cellColors[i % cellColors.size]) { p -> p.cellsMv.getOrNull(i)?.div(1000.0) } },
                from, to, Fmt.V, 3, gapMs = gap)
        }
        val left = listOf(
            sum,
            chartCard(tr("Заряд, %", "Charge, %"), LineIcon.BATTERY, "soc", socChart),
            chartCard(tr("Струм", "Current") + ", " + Fmt.A, LineIcon.BOLT, "current", curChart,
                listOf(tr("заряд", "charge") to Palette.success, tr("розряд", "discharge") to Palette.danger)),
            chartCard(tr("Потужність", "Power") + ", " + Fmt.W, LineIcon.BOLT, "power", pwrChart),
        )
        val right = listOf(
            chartCard(tr("Напруга", "Voltage") + ", " + Fmt.V, LineIcon.PULSE, "voltage", vChart),
            chartCard(tr("Температура, °C", "Temperature, °C"), LineIcon.THERMO, "temp", tChart,
                listOf(tr("макс.", "max") to Palette.warning, tr("мін.", "min") to Palette.accent)),
            chartCard(tr("Різниця комірок", "Cell difference") + ", " + Fmt.MV, LineIcon.CELLS, "delta", dChart),
            chartCard(tr("Комірки", "Cells") + ", " + Fmt.V, LineIcon.CELLS, "cells", cChart,
                (0 until cellsN).map { cellNo(it + 1) to cellColors[it % cellColors.size] }),
        )
        val ins = insightCards()
        columns(ins.take(2) + left, right + ins.drop(2))
        body.addView(ui.muted(tr("Торкнись графіка й веди пальцем — побачиш значення в будь-який момент.",
                "Touch a chart and slide your finger to see the value at any moment."), 12.5f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
    }

    /**
     * Картки за останні [INSIGHT_DAYS] днів (не залежать від вибраного періоду): відключення світла,
     * найнижчий заряд по днях, хто відстає наприкінці заряду, зарядний. Аналіз скрінів Logic Power faric 19.10.
     */
    private fun insightCards(): List<View> {
        val pts = insightPts
        val out = ArrayList<View>()
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("dd.MM", java.util.Locale.ROOT)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)

        // ---- відключення ----
        val oc = ui.card(8)
        oc.addView(labelRow(tr("Відключення світла", "Power outages"), LineIcon.BOLT, "outages"))
        if (pts == null) {
            oc.addView(ui.muted(tr("Завантажую історію…", "Loading history…"), 13f))
            out += oc
            return out
        }
        val outages = Outages.find(pts)
        if (outages.isEmpty()) {
            oc.addView(ui.muted(tr("За ${INSIGHT_DAYS} днів у записаній історії відключень не знайдено. Застосунок помічає їх сам: батарея " +
                "кілька хвилин віддає струм — світла нема; знову заряджається чи стоїть — світло є. Записується, поки застосунок відкритий, " +
                "а з «Записом у фоні» («Ще») — і коли закритий.",
                "No outages found in the recorded history for ${INSIGHT_DAYS} days. The app finds them itself: the battery " +
                "discharges for a few minutes — no power; charges or is idle again — power is back. Recorded while the app is open, " +
                "and with \"Background recording\" (\"More\") — also when closed."), 13f))
        } else {
            val week = outages.filter { it.end > now - 7 * DailyMin.DAY_MS }
            val sumBox = ArrayList<View>()
            summaryValues.clear()
            sumBox += summaryTile(tr("за 7 днів", "7 days"), "${week.size} " + Fmt.plural(week.size, "відключення", "відключення",
                    "відключень", "outage", "outages"))
            sumBox += summaryTile(tr("без світла", "without power"), Fmt.duration(week.sumOf { it.durationMs } / 3_600_000.0))
            val weekWh = week.sumOf { it.wh }
            sumBox += summaryTile(tr("віддала", "discharged"),
                if (weekWh >= 1000) Fmt.num(weekWh / 1000, 1) + " " + Fmt.KWH else Fmt.num(weekWh, 0) + " " + Fmt.WH)
            sumBox += summaryTile(tr("найнижче", "lowest"), (week.minOfOrNull { it.socTo }?.let { "$it %" } ?: "—"))
            oc.addView(ui.grid(sumBox, perRow = 2, gapDp = 6), ui.matchWrap())
            for (o in outages.sortedByDescending { it.start }.take(5)) {
                // Остання точка історії — ще розряд, і вона свіжа: відключення триває зараз.
                val ongoing = o.ongoing && now - o.end < 5 * 60_000L
                val box = ui.column(4).apply {
                    background = ui.rounded(Palette.surfaceAlt, if (ongoing) Palette.danger else Palette.border, radiusDp = 12)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    isClickable = true
                    setOnClickListener { showInfo("outages") }
                }
                // T31 (скрін faric 20.10 05:14): у демо JK «99 % → 62…» — права частина (чотиризначні Вт·год) витісняла
                // відсоток. Тепер у кожному рядку важлива частина ніколи не обрізається, а другорядна спершу зменшує шрифт.
                val title = if (ongoing) tr("Світла нема з ${hm.format(java.util.Date(o.start))}", "No power since ${hm.format(java.util.Date(o.start))}")
                else "${day.format(java.util.Date(o.start))} ${hm.format(java.util.Date(o.start))} → ${hm.format(java.util.Date(o.end))}"
                val titleColor = if (ongoing) Palette.danger else Palette.text
                box.addView(outageRow(title, Fmt.duration((if (ongoing) now - o.start else o.durationMs) / 3_600_000.0),
                    keepLeft = false, color = titleColor, valueColor = titleColor, sizeSp = 14f), ui.matchWrap())
                box.addView(outageRow("${o.socFrom} % → ${o.socTo} %", "${Fmt.num(o.ah, 1)} ${Fmt.AH} ≈ ${Fmt.num(o.wh, 0)} ${Fmt.WH}",
                    keepLeft = true, color = Palette.muted, valueColor = Palette.text), ui.matchWrap())
                box.addView(outageRow(tr("в середньому ${Fmt.watts(o.avgW)}", "average ${Fmt.watts(o.avgW)}"),
                    tr("пік ${Fmt.watts(o.peakW)}", "peak ${Fmt.watts(o.peakW)}"),
                    keepLeft = true, color = Palette.muted, valueColor = Palette.text), ui.matchWrap())
                oc.addView(box, ui.matchWrap())
            }
            oc.addView(ui.muted(tr("Видно лише години, коли застосунок записував (відкритий або «Запис у фоні»).",
                    "Shows only hours when the app was recording (open or \"Background recording\")."), 12.5f))
        }
        out += oc

        // ---- найнижчий заряд по днях ----
        val days = DailyMin.of(pts, java.util.TimeZone.getDefault().getOffset(now).toLong()).takeLast(10)
        val dc = ui.card(8)
        dc.addView(labelRow(tr("Найнижчий заряд по днях", "Lowest charge by day"), LineIcon.CHART, "dailyMin"))
        if (days.isEmpty()) {
            dc.addView(ui.muted(tr("Ще немає записаних днів.", "No recorded days yet."), 13f))
        } else {
            val gen = marks.generatorDays(address)
            val keys = days.map { dayKey.format(java.util.Date(it.dayStart + 12 * 3_600_000L)) }
            val bars = DayBarsView(host).apply {
                set(days.mapIndexed { i, d -> DayBarsView.Bar(day.format(java.util.Date(d.dayStart + 12 * 3_600_000L)).take(2), d.minSoc, keys[i] in gen) })
                onDay = { i ->
                    val on = marks.toggleGenerator(address, keys[i])
                    host.toast(day.format(java.util.Date(days[i].dayStart + 12 * 3_600_000L)) +
                        if (on) tr(" — позначено: генератор", " — marked: generator") else tr(" — позначку знято", " — mark removed"))
                    render()
                }
            }
            dc.addView(bars, ui.matchWrap())
            dc.addView(ui.muted(tr("Торкнись дня — позначити «Г» (працював генератор). Для батареї світло й генератор однакові, тож сам застосунок їх не розрізнить.",
                "Tap a day to mark it \"G\" (generator ran). Grid power and a generator look the same to the battery, so the app cannot tell them apart."), 12.5f))
        }
        out += dc

        // ---- хто відстає ----
        Laggard.find(pts)?.let { r ->
            val lc = ui.card(8)
            lc.addView(labelRow(tr("Хто відстає наприкінці заряду", "Lagging cell at end of charge"), LineIcon.CELLS, "laggard"))
            r.leader?.let { lead ->
                val often = cellNo(lead + 1) + " · " + tr("${r.lowestCount[lead]} з ${r.episodes}", "${r.lowestCount[lead]} of ${r.episodes}")
                lc.addView(fact(tr("Найчастіше найнижча", "Most often lowest"), often, "laggard", Palette.warning, known = true))
            }
            lc.addView(fact(tr("Різниця в кінці заряду", "Difference at end of charge"), (if (r.deltas.min() == r.deltas.max()) "${r.deltas.min()}" else "${r.deltas.min()}–${r.deltas.max()}") + " ${Fmt.MV}", "laggard",
                if (r.deltas.max() >= BatterySnapshot.Verdict.WATCH_FROM_MV) Palette.warning else Palette.success, known = true))
            lc.addView(ui.muted(tr("Це не поломка: одна комірка вгорі трохи «менша», плата її підтягує. Тривожно, якщо різниця в кінці заряду росте від тижня до тижня.",
                "This is not a fault: one cell is a bit \"smaller\" at the top, the BMS pulls it along. Worry if the end-of-charge difference grows week by week."), 12.5f))
            out += lc
        }

        // ---- зарядний ----
        ChargerCheck.of(pts)?.let { c ->
            val cc = ui.card(8)
            cc.addView(labelRow(tr("Зарядний", "Charger"), LineIcon.BOLT, "charger"))
            val n = pts.lastOrNull()?.cellsMv?.size?.takeIf { it > 0 } ?: 4
            val perCell = Fmt.volts(c.maxV / n, 2)
            val color = if (c.verdict == ChargerCheck.Verdict.OK) Palette.success else Palette.warning
            cc.addView(fact(tr("Найвища напруга на заряді", "Highest charge voltage"), tr("${Fmt.volts(c.maxV)} · ${perCell} на комірку",
                    "${Fmt.volts(c.maxV)} · ${perCell} per cell"), "charger", color, known = true))
            cc.addView(fact(tr("Оцінка", "Verdict"), c.verdict.title, "charger", color, known = true))
            cc.addView(ui.muted(tr("За ${INSIGHT_DAYS} днів записаної історії. Для 4 комірок LiFePO4 норма — 13,8–14,6 В (3,45–3,65 В на комірку). Орієнтир, не діагноз.",
                "From ${INSIGHT_DAYS} days of recorded history. For 4 LiFePO4 cells the norm is 13.8–14.6 V (3.45–3.65 V per cell). A guide, not a diagnosis."), 12.5f))
            out += cc
        }
        return out
    }

    /**
     * Плитка «підпис / значення» (підсумки відключень). T30: обидва рядки — рівно в один рядок, щоб плитки в ряду були
     * однієї висоти. Значення не влазить — спершу менший шрифт (`TextFit`, однаковий у всіх плитках ряду), і лише якщо
     * й мінімальний не влазить — «…». Розмір запам'ятовуємо (`summaryValuePx`), як `miniCaptionPx`: картки
     * перебудовуються — без цього шрифт щоразу «стрибав» би 17 → менше.
     */
    private fun summaryTile(top: String, value: String): View = ui.column(2).apply {
        background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
        setPadding(ui.dp(11), ui.dp(9), ui.dp(11), ui.dp(9))
        addView(oneLine(ui.text(top, 12f, Palette.muted)), ui.matchWrap())
        val v = numbers(ui.text(Ui.keepUnits(value), 17f, Palette.text, medium = true))
        oneLine(v)
        if (summaryValuePx > 0f) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, summaryValuePx)
        summaryValues += v
        v.addOnLayoutChangeListener { view, l, _, r, _, _, _, _, _ ->
            val tv = view as TextView
            val avail = (r - l - tv.paddingLeft - tv.paddingRight).toFloat()
            val size = TextFit.size(tv.textSize, tv.paint.measureText(tv.text.toString()), avail, ui.dp(SUMMARY_MIN_DP).toFloat())
            if (size < tv.textSize - 0.5f) {
                summaryValuePx = TextFit.shared(summaryValuePx, size)
                tv.post { for (o in summaryValues) o.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, summaryValuePx) }
            }
        }
        addView(v, ui.matchWrap())
    }

    // ---------- «Плата» ----------

    private fun renderBoard(s: BatterySnapshot) {
        val health = ui.card(8)
        health.addView(labelRow(tr("Здоров'я", "Health"), LineIcon.BATTERY, "soh"))
        val rated = Health.ratedAh(s.model)
        rated?.let { health.addView(fact(tr("На етикетці", "On the label"), "$it ${Fmt.AH}", "soh")) }
        health.addView(fact(tr("Повна (знає плата)", "Full (BMS value)"), Fmt.ampHours(s.fullAh), "full"))
        Health.sohPercent(s.fullAh, rated)?.let { health.addView(fact(tr("Від етикетки", "Vs label"), "$it %", "soh",
                if (it >= 80) Palette.success else Palette.warning)) }
        health.addView(fact(tr("Залишок", "Remaining"), Fmt.ampHours(s.remainAh), "remain"))
        health.addView(fact(tr("Цикли", "Cycles"), s.cycles.toString(), "cycles"))
        // Номінал LiFePO4 — 3,2 В на комірку (Aolithium 4 комірки = 12,8 В; Logic Power — скільки комірок у плати).
        val nominalV = if (s.cellCount > 0) s.cellCount * 3.2 else 12.8
        health.addView(fact(tr("Енергія повної", "Full energy"), "≈ " + Fmt.num(s.fullAh * nominalV / 1000, 2) + " " + Fmt.KWH, "energy"))
        s.jk?.let { j ->
            health.addView(fact(tr("Здоров'я (каже плата)", "Health (BMS value)"), "${j.soh} %", "jksoh",
                    if (j.soh >= 80) Palette.success else Palette.warning))
            if (j.cycleCapacityAh > 0) health.addView(fact(tr("Пройдено всього", "Total throughput"),
                Fmt.num(j.cycleCapacityAh, 0) + " " + Fmt.AH, "cyclecap"))
            j.stageTitle?.let { health.addView(fact(tr("Етап заряду", "Charge stage"), it, "stage")) }
        }

        val board = ui.card(8)
        board.addView(labelRow(tr("Плата", "BMS"), LineIcon.CHIP, "board"))
        val jkx = s.jk
        if (jkx != null || Batteries.kindOf(host, address) == BmsMatch.Kind.JK && demo == null) {
            board.addView(fact(tr("Тип", "Type"), "JK BMS (Jikong)", "jkboard", known = true))
        } else {
            board.addView(fact(tr("Тип", "Type"), "JBD (Jiabaida)", "board", known = true))
        }
        s.model?.let { board.addView(fact(tr("Назва плати", "BMS name"), it, "model")) }
            ?: if (data == Data.NONE) board.addView(fact(tr("Назва плати", "BMS name"), DASH, "model")) else Unit
        s.firmware?.let { board.addView(fact(tr("Версія програми", "Firmware version"), it, "firmware")) }
        s.made?.let { board.addView(fact(tr("Дата виготовлення", "Manufacture date"), it.toString(), "made")) }
        jkx?.let { j ->
            j.hw?.let { board.addView(fact(tr("Версія заліза", "Hardware version"), it, "jkboard")) }
            board.addView(fact(tr("Формат даних", "Data format"), j.layout, "jkboard"))
            if (j.runtimeSec > 0) board.addView(fact(tr("Час роботи плати", "BMS uptime"), Fmt.runtime(j.runtimeSec), "runtime"))
            board.addView(fact(tr("Зарядний", "Charger"),
                if (j.chargerPlugged) tr("підключено", "connected") else tr("не підключено", "not connected"), "jkboard"))
        }
        board.addView(fact(tr("Комірок", "Cells"), s.cellCount.toString(), "cells"))
        board.addView(fact(tr("Датчиків температури", "Temperature sensors"), s.temperatures.size.toString(), "temp"))
        if (demo == null) {
            board.addView(fact(tr("Адреса", "Address"), BmsMatch.shortAddress(address), "onelink"))
            BleScanner.lastRssi[address]?.let { board.addView(fact(tr("Сигнал (у пошуку)", "Signal (in search)"), Signal.describe(it),
                    "signal", if (Signal.tooFar(it)) Palette.warning else Palette.text)) }
        }

        val right = ArrayList<View>()
        if (s.hasProblem) right += problemCard(s)
        if (s.errorCounts == null && s.jk == null) {
            val c = ui.card(8)
            c.addView(labelRow(tr("Захисти за весь час", "Protections, all time"), LineIcon.LIST, "counters"))
            c.addView(ui.muted(tr("Скільки разів плата вмикала кожен захист — з'явиться після підключення.",
                    "How many times the BMS triggered each protection — shown after connecting."), 13f))
            right += c
        }
        s.errorCounts?.let { counts ->
            val c = ui.card(8)
            c.addView(labelRow(tr("Захисти за весь час", "Protections, all time"), LineIcon.LIST, "counters"))
            c.addView(ui.muted(tr("Скільки разів плата вмикала кожен захист. Торкнись рядка — що це і що робити.",
                    "How many times the BMS triggered each protection. Tap a row to see what it is and what to do."), 12.5f))
            for ((name, n) in counts) c.addView(fact(JbdParser.counterTitle(name), n.toString(), Glossary.forCounter(name), if (n > 0) Palette.warning else Palette.muted))
            // Назви лічильників — ключі з протоколу (українські, і в LastSnapshot теж) — тож шукаємо через довідник, не за текстом.
            val over = counts.firstOrNull { Glossary.forCounter(it.first) == "cnt_cell_over" }?.second ?: 0
            Health.overchargeAdvice(over)?.let { advice ->
                c.addView(ui.card(6, fill = Palette.warningFill, stroke = Palette.warning).apply {
                    addView(ui.text(tr("Порада", "Tip"), 15f, Palette.warning, medium = true))
                    addView(ui.text(advice, 14f, Palette.text))
                }, ui.matchWrap())
            }
            right += c
        }
        columns(listOf(health, board), right)
    }

    // ---------- «Ще» ----------

    private fun renderMore() {
        val actions = ui.card()
        actions.addView(labelRow(tr("Дії", "Actions"), LineIcon.SLIDERS, null))
        val buttons = ArrayList<View>()
        if (demo == null) buttons += ui.iconButton(LineIcon.PEN, tr("Перейменувати", "Rename"), Ui.Kind.NORMAL) { showRename() }
        buttons += ui.iconButton(LineIcon.REPEAT, tr("Підключити ще раз", "Reconnect"), Ui.Kind.NORMAL) { reconnect() }
        buttons += ui.button(tr("Журнал зв'язку", "Connection log"), Ui.Kind.NORMAL) { showLog() }
        if (demo == null && batteries.find(address) != null) buttons += ui.button(tr("Прибрати зі списку", "Remove from list"),
                Ui.Kind.QUIET) { showForget() }
        actions.addView(ui.grid(buttons), ui.matchWrap())
        actions.addView(ui.muted(tr("Застосунок лише читає плату — нічого в ній не змінює. Поки він підключений, Aolithium на іншому телефоні не підключиться: " +
            "сховай цей застосунок — через 5 с зв'язок звільниться.",
            "The app only reads the BMS — it changes nothing in it. While it is connected, Aolithium on another phone cannot connect: " +
            "hide this app — the link is freed after 5 s."), 12.5f))

        val hist = ui.card(8)
        hist.addView(labelRow(tr("Історія", "History"), LineIcon.CHART, "history"))
        val (days, bytes) = historyBytes ?: history.size(address).also { historyBytes = it }
        val kb = Fmt.num(bytes / 1024.0, 0)
        hist.addView(fact(tr("Записано", "Recorded"),
            if (demo != null) tr("демо — не пишеться", "demo — not recorded") else tr("$days дн. · $kb КБ", "$days d · $kb KB"),
            "history", known = true))
        hist.addView(fact(tr("Як часто", "How often"),
            if (demo == null && bg.isOn(address)) tr("раз на 10 с відкрито · ~5 хв у фоні", "every 10 s open · ~5 min in background")
            else tr("раз на 10 с, поки відкрито", "every 10 s while open"),
            "history", known = true))
        hist.addView(fact(tr("Скільки зберігається", "Kept for"), tr("${HistoryStore.KEEP_DAYS} днів", "${HistoryStore.KEEP_DAYS} days"),
                "history", known = true))
        if (demo == null && days > 0) {
            hist.addView(ui.iconButton(LineIcon.LIST, tr("Зберегти CSV (таблиця)", "Save CSV (table)"), Ui.Kind.NORMAL) { saveCsv() },
                    ui.matchWrap())
            hist.addView(ui.button(tr("Стерти історію цієї батареї", "Clear this battery's history"),
                    Ui.Kind.DANGER) { showClearHistory() }, ui.matchWrap())
        }

        val dict = ui.card(6)
        dict.addView(labelRow(tr("Довідник: що означає кожен показник", "Glossary: what each value means"), LineIcon.BOOK, null))
        for ((g, entries) in Glossary.byGroup()) {
            dict.addView(ui.text(g.title, 14f, Palette.accent, medium = true).apply { setPadding(0, ui.dp(8), 0, 0) })
            for (en in entries) dict.addView(infoRow(en.title, en.id), ui.matchWrap())
        }

        val help = ui.card()
        help.addView(labelRow(tr("Інструкція", "Guide"), LineIcon.HELP, null))
        help.addView(ui.muted(tr("Зі схемами й знімками: усі вкладки, запис у фоні, сповіщення, віджет, демо без батареї.",
                "With diagrams and screenshots: all tabs, background recording, notifications, widget, demo without a battery."), 13f))
        help.addView(ui.iconButton(LineIcon.HELP, tr("Відкрити інструкцію", "Open the guide"), Ui.Kind.NORMAL) { host.openHelp("home") },
                ui.matchWrap())

        columns(listOf(backgroundCard(), alertsCard(), langCard(), actions, hist, help), listOf(dict))
    }

    /** T12: «Мова / Language» — обидві назви, щоб картку знайшов і той, хто не читає поточною мовою. */
    private fun langCard(): View {
        val card = ui.card()
        card.addView(labelRow("Мова · Language", LineIcon.GLOBE, null)) // uk-only: обидві мови навмисно
        val row = ArrayList<View>()
        row += ui.choice("Українська", !Lang.isEn) { host.setLanguage(Lang.Code.UK) } // uk-only
        row += ui.choice("English", Lang.isEn) { host.setLanguage(Lang.Code.EN) }
        card.addView(ui.grid(row), ui.matchWrap())
        card.addView(ui.muted(tr("Сповіщення й віджет теж перейдуть на вибрану мову.",
                "Notifications and the widget switch language too."), 12.5f))
        return card
    }

    /**
     * «Запис у фоні» (v0.2.5, T3): перемикач для цієї батареї, останній запис, і що ще заважає
     * (сповіщення заборонені, Android обмежує батарею для застосунку).
     */
    private fun backgroundCard(): View {
        val on = demo == null && bg.isOn(address)
        val card = if (on) ui.card(8, fill = Palette.accentFill, stroke = Palette.accent) else ui.card(8)
        card.addView(labelRow(tr("Запис у фоні", "Background recording"), LineIcon.REPEAT, "background"))
        if (demo != null) {
            card.addView(ui.muted(tr("У демо не працює — лише зі справжньою батареєю.",
                    "Does not work in demo — only with a real battery."), 13f))
            return card
        }
        card.addView(ui.muted(tr("Раз на ~5 хв, навіть коли застосунок закритий: коротко підключається, записує показники й відпускає батарею. " +
            "Так видно відключення світла, що були без тебе.",
            "Every ~5 min, even when the app is closed: connects briefly, records values and releases the battery. " +
            "So you see power outages that happened while you were away."), 13f))
        card.addView(ui.grid(listOf(
            ui.choice(tr("Увімкнено", "On"), on) { setBackground(true) },
            ui.choice(tr("Вимкнено", "Off"), !on) { setBackground(false) },
        )), ui.matchWrap())
        if (on) {
            card.addView(fact(tr("Останній запис", "Last record"), Background.status(bg.result(address)), "background", known = true))
            if (!RecordService.running) card.addView(ui.muted(tr(
                "Служба запису зараз не працює — Android її зупинив. Відкрий застосунок ще раз або вимкни й увімкни.",
                "The recording service is not running — Android stopped it. Open the app again or turn it off and on."), 13f))
            if (BtAccess.missingPermissions(host).isNotEmpty()) {
                card.addView(ui.muted(tr("Немає дозволу на Bluetooth — без нього фон не прочитає батарею.",
                        "No Bluetooth permission — without it, background recording cannot read the battery."), 13f))
                card.addView(ui.button(tr("Дати дозвіл", "Allow"), Ui.Kind.PRIMARY) { host.requestAccess() }, ui.matchWrap())
            } else if (!host.notificationsAllowed) {
                card.addView(ui.muted(tr("Сповіщення заборонені: запис працює, але в шторці його не видно, і вимкнути можна лише тут.",
                    "Notifications are blocked: recording works, but it is not shown in the notification shade and can be turned off only here."), 13f))
                card.addView(ui.button(tr("Дозволити сповіщення", "Allow notifications"), Ui.Kind.NORMAL) { host.requestNotifications() },
                        ui.matchWrap())
            }
            if (!host.batteryUnrestricted) {
                card.addView(ui.muted(tr("Android може приспати запис, щоб берегти батарею телефона. Щоб писав рівно — дозволь «без обмежень».",
                    "Android may pause recording to save the phone battery. For steady recording, allow \"Unrestricted\"."), 13f))
                card.addView(ui.button(tr("Не обмежувати у фоні", "Don't restrict in background"),
                        Ui.Kind.NORMAL) { host.askUnrestrictedBattery() }, ui.matchWrap())
            }
        }
        return card
    }

    /**
     * «Сповіщення» (v0.3, T4): перемикач кожного виду (спільні для всіх батарей, за замовчуванням усі увімкнені).
     * faric 19.10 19:44: «всі типи сповіщень… якщо можеш про щось ще — сповіщай».
     */
    private fun alertsCard(): View {
        val card = ui.card(8)
        card.addView(labelRow(tr("Сповіщення", "Notifications"), LineIcon.BELL, "alerts"))
        val bgOn = demo == null && bg.isOn(address)
        card.addView(ui.muted(
            if (demo != null) tr("Демо-батарея не сповіщає. Перемикачі спільні для всіх батарей — їх можна налаштувати й тут.",
                "The demo battery does not notify. Switches are shared by all batteries — you can set them here too.")
            else if (bgOn) tr("Приходять і коли застосунок закритий (раз на ~5 хв). Лише поки телефон поруч із батареєю — Bluetooth дістає кілька метрів.",
                "They come even when the app is closed (every ~5 min). Only while the phone is near the battery — Bluetooth reaches a few meters.")
            else tr("Зараз — лише поки відкритий цей екран. Щоб приходили й коли застосунок закритий, увімкни «Запис у фоні» вище.",
                "Now — only while this screen is open. To get them when the app is closed, turn on \"Background recording\" above."),
            13f,
        ))
        if (!host.notificationsAllowed) {
            card.addView(ui.muted(tr("Android заборонив сповіщення цьому застосунку — у шторці їх не буде.",
                    "Android blocked notifications for this app — none will show in the shade."), 13f))
            card.addView(ui.button(tr("Дозволити сповіщення", "Allow notifications"), Ui.Kind.PRIMARY) { host.requestNotifications() },
                    ui.matchWrap())
        }
        val toggles = Alerts.Kind.entries.map { k ->
            val on = alertPrefs.isOn(k)
            ui.choice("${k.title}\n${if (on) tr("увімкнено", "on") else tr("вимкнено", "off")}", on) {
                alertPrefs.set(k, !on)
                if (!on) host.requestNotifications()
                render()
            }
        }
        card.addView(ui.grid(toggles, perRow = 1), ui.matchWrap())
        if (demo == null) {
            card.addView(ui.iconButton(LineIcon.BELL, tr("Перевірити сповіщення", "Test notifications"), Ui.Kind.NORMAL) {
                host.requestNotifications()
                Notifier.test(host, address, snapshot ?: stale?.first)
            }, ui.matchWrap())
        }
        card.addView(ui.muted(tr("Кожне — один раз, доки стан не мине. Захист «перезаряд комірки» наприкінці заряду — це «Батарея повна», не тривога.",
            "Each comes once, until the state passes. The \"cell overcharge\" protection at the end of charge means \"Battery full\", not an alarm."), 12.5f))
        return card
    }

    private fun setBackground(on: Boolean) {
        if (on == bg.isOn(address)) return
        bg.set(address, on)
        if (on && batteries.find(address) == null) batteries.remember(address, Batteries.scanNames[address], Batteries.kindOf(host, address))
        RecordService.ensure(host)
        if (on) {
            if (BtAccess.missingPermissions(host).isNotEmpty()) host.requestAccess() else host.requestNotifications()
        }
        render()
    }

    private fun saveCsv() {
        host.saveFile(HistoryCsv.fileName(title, System.currentTimeMillis()), "text/csv") { out -> history.writeCsv(address, out) }
    }

    // ---------- спільні картки ----------

    private fun reconnect() {
        LinkHolder.release(this)
        val l = LinkHolder.restart(host, address)
        link = l
        l.listener = this
        status = frozenStatus ?: l.lastStatus
        render()
    }

    private fun problemCard(s: BatterySnapshot): View {
        val card = ui.card(fill = Palette.dangerFill, stroke = Palette.danger)
        card.addView(ui.label(tr("Що спрацювало", "What triggered"), LineIcon.SHIELD))
        for (p in s.protections) {
            card.addView(ui.text(p.title, 16f, Palette.text, medium = true))
            card.addView(ui.muted(p.explain, 14f))
        }
        for (bit in s.unknownProtectionBits) {
            card.addView(ui.text(tr("Невідомий сигнал плати (біт $bit)", "Unknown BMS signal (bit $bit)"), 16f, Palette.text,
                    medium = true))
            card.addView(ui.muted(tr("Плата повідомляє те, чого ми ще не знаємо. Надішли Claude скрін цього екрана.",
                    "The BMS reports something we do not know yet. Send Claude a screenshot of this screen."), 14f))
        }
        return card
    }

    // ---------- рядки з «ⓘ» ----------

    /** Підпис картки з «ⓘ» праворуч (якщо є запис у довіднику). */
    private fun labelRow(text: String, icon: LineIcon, info: String?): View {
        val row = ui.row(8)
        row.addView(ui.label(text, icon), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (info != null) row.addView(infoMark(info))
        return row
    }

    private fun infoMark(id: String): View = ui.text(INFO, 18f, Palette.accent).apply {
        gravity = Gravity.CENTER
        minWidth = ui.dp(Ui.MIN_TOUCH_DP)
        minHeight = ui.dp(Ui.MIN_TOUCH_DP)
        contentDescription = tr("Що це", "What is this")
        isClickable = true
        background = ui.pressable(ui.rounded(Palette.transparent, radiusDp = 24), 24)
        setOnClickListener { showInfo(id) }
    }

    /** «Назва ············ значення ⓘ» — весь рядок натискається. */
    private fun fact(name: String, value: CharSequence, info: String, color: Int = Palette.text, known: Boolean = false): View {
        val none = data == Data.NONE && !known
        return ui.fact(name, (if (none) DASH else value).toString() + "\u00A0\u00A0$INFO", if (none) Palette.muted else color).apply { clickableInfo(this, info) }
    }

    private fun infoRow(title: String, id: String): View = ui.row(8).apply {
        addView(ui.text(title, 15f, Palette.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text("›", 22f, Palette.muted))
        setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
        minimumHeight = ui.dp(Ui.MIN_TOUCH_DP)
        clickableInfo(this, id)
    }

    private fun pillInfo(label: String, t: Ui.Tone, id: String): View = ui.pill(label, t).apply {
        minHeight = ui.dp(36)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setOnClickListener { showInfo(id) }
    }

    private fun clickableInfo(v: View, id: String) {
        v.isClickable = true
        v.isFocusable = true
        v.background = ui.pressable(ui.rounded(Palette.transparent, radiusDp = 8), 8)
        v.setOnClickListener { showInfo(id) }
    }

    private fun tempColor(t: Double) = when {
        t < 0 || t > 50 -> Palette.danger
        t < 10 || t > 40 -> Palette.warning
        else -> Palette.text
    }

    private fun verdictColor(s: BatterySnapshot) = when (s.deltaVerdict) {
        BatterySnapshot.Verdict.GOOD -> Palette.success
        BatterySnapshot.Verdict.WATCH -> Palette.warning
        BatterySnapshot.Verdict.BAD -> Palette.danger
        null -> Palette.text
    }

    // ---------- вікна ----------

    /** Віджет з цими ж даними: великий і малий, плюс «нема даних» і «ще не було зв'язку» — для перевірки малюнка. */
    private fun showWidgetPreview(tries: Int = 0) {
        // Демо дає перший знімок через секунду-дві після відкриття — дочекатись, інакше всі картинки «порожні»
        // (знімки CI 19.10 21:07 так і вийшли).
        if (snapshot == null && demo != null && tries < 10) {
            handler.postDelayed({ showWidgetPreview(tries + 1) }, 1_000)
            return
        }
        val now = System.currentTimeMillis()
        val box = ui.column(10)
        val dens = host.resources.displayMetrics.density
        fun add(label: String, m: WidgetModel.Model, wDp: Int, hDp: Int) {
            box.addView(ui.muted(label, 12.5f))
            val img = ImageView(host).apply {
                setImageBitmap(WidgetArt.draw(m, wDp, hDp, dens))
                adjustViewBounds = true
            }
            box.addView(img, if (wDp > hDp) ui.matchWrap() else android.widget.LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)))
        }
        val s = snapshot ?: stale?.first
        val at = if (snapshot != null) snapshotAt else stale?.second
        add(tr("Великий 4 × 2", "Large 4 × 2"), WidgetModel.of(title, s, at, now), 320, 160)
        add(tr("Малий 2 × 2", "Small 2 × 2"), WidgetModel.of(title, s, at, now), 160, 160)
        add(tr("Нема даних 2 год", "No data 2 h"), WidgetModel.of(title, s, (at ?: now) - 2 * 3_600_000L, now), 320, 160)
        add(tr("Ще не було зв'язку", "Never connected"), WidgetModel.of(title, null, null, now), 160, 160)
        val dlg = host.overlay.dialog(
            tr("Віджет", "Widget"), "",
            actions = listOf(tr("Закрити", "Close") to Ui.Kind.QUIET),
            onAction = { host.overlay.dismiss() },
            extra = box,
        )
        host.overlay.show(DLG_WIDGETS, dlg)
    }

    private fun showInfo(id: String) {
        val e = Glossary.get(id) ?: return
        val box = ui.column(10)
        e.aolithium?.let { box.addView(ui.muted(tr("В Aolithium 1.3: «$it»", "In Aolithium 1.3: \"$it\""), 13f)) }
        box.addView(section(tr("Що це", "What it is"), e.what))
        if (e.normal != "—") box.addView(section(tr("Норма", "Normal"), e.normal))
        if (e.worry != "—") box.addView(section(tr("Коли звернути увагу", "When to worry"), e.worry))
        val dlg = host.overlay.dialog(
            e.title, "",
            actions = listOf(tr("Закрити", "Close") to Ui.Kind.QUIET),
            onAction = { host.overlay.dismiss() },
            extra = box,
        )
        host.overlay.show(DLG_INFO + id, dlg)
    }

    private fun section(title: String, text: String): View = ui.column(4).apply {
        addView(ui.text(title, 14f, Palette.accent, medium = true))
        addView(ui.text(text, 15f, Palette.text))
    }

    private fun showClearHistory() {
        val box = host.overlay.dialog(
            tr("Стерти історію «$title»?", "Clear history of \"$title\"?"),
            tr("Зникнуть усі записані графіки цієї батареї на цьому телефоні. Саму батарею це не чіпає. Повернути не можна.",
                    "All recorded charts of this battery on this phone will be deleted. The battery itself is not affected. This cannot be undone."),
            actions = listOf(tr("Стерти", "Clear") to Ui.Kind.DANGER, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0) history.clear(address) {
                    historyBytes = null
                    points = emptyList()
                    pointsFor = null
                    render()
                }
            },
        )
        host.overlay.show(DLG_CLEAR, box)
    }

    private fun showRename() {
        val field = EditText(host).apply {
            setText(renameDraft ?: batteries.find(address)?.nickname.orEmpty())
            hint = Batteries.scanNames[address] ?: tr("Напр., «Aolithium вдома»", "E.g. \"Aolithium home\"")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Palette.text)
            setHintTextColor(Palette.faint)
            textSize = 17f
            background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minimumHeight = ui.dp(Ui.MIN_TOUCH_DP)
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renameDraft = s?.toString()
                }
            })
        }
        val box = host.overlay.dialog(
            tr("Назва батареї", "Battery name"),
            tr("Як показувати її в «Моїх батареях». Порожньо — назва з Bluetooth.",
                    "How to show it in \"My batteries\". Empty — the Bluetooth name."),
            actions = listOf(tr("Зберегти", "Save") to Ui.Kind.PRIMARY, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    if (batteries.find(address) == null) batteries.remember(address, Batteries.scanNames[address], Batteries.kindOf(host, address))
                    batteries.rename(address, field.text.toString())
                    renderHead()
                }
                renameDraft = null
                host.overlay.dismiss()
            },
            extra = field,
        )
        host.overlay.show(DLG_RENAME, box)
    }

    private fun showLog() {
        val text = LinkLog.text().ifBlank { tr("Журнал порожній.", "The log is empty.") }
        val body = ui.text(text.takeLast(LOG_SHOW_CHARS), 12f, Palette.muted).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val box = host.overlay.dialog(
            tr("Журнал зв'язку", "Connection log"),
            tr("Що відправили (→) і що прийшло (←). Можна переслати Claude, якщо щось не так.",
                    "What was sent (→) and what came back (←). You can send it to Claude if something is wrong."),
            actions = listOf(tr("Поділитися", "Share") to Ui.Kind.PRIMARY, tr("Закрити", "Close") to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) host.shareText(tr("Aolithium Live — журнал зв'язку", "Aolithium Live — connection log"),
                        host.versionLine + "\n" + text)
                host.overlay.dismiss()
            },
            extra = body,
        )
        host.overlay.show(DLG_LOG, box)
    }

    private fun showForget() {
        val box = host.overlay.dialog(
            tr("Прибрати «$title» зі списку?", "Remove \"$title\" from the list?"),
            tr("Зникне лише з «Моїх батарей» у цьому застосунку. Сама батарея й Aolithium нічого не помітять. Знайти знову — пошуком.",
                    "It disappears only from \"My batteries\" in this app. The battery and Aolithium will not notice. To find it again, use search."),
            actions = listOf(tr("Прибрати", "Remove") to Ui.Kind.DANGER, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0) {
                    batteries.forget(address)
                    LinkHolder.stopNow()
                    host.goHome()
                }
            },
        )
        host.overlay.show(DLG_FORGET, box)
    }

    private companion object {
        const val INFO = "ⓘ"
        const val DASH = "—"
        const val KEY_TAB = "tab"
        const val KEY_RANGE = "range"
        const val DLG_INFO = "info:"
        const val DLG_CLEAR = "clearHistory"
        const val KEY_DRAFT = "renameDraft"
        const val DLG_RENAME = "rename"
        const val DLG_LOG = "log"
        /** Вікно «Зв'язок» з повним поясненням (T25). */
        const val DLG_LINK = "link"
        /** Мінімальна висота рядка «Зв'язок»: кнопка «Ще раз» (48 dp) + відступи — ховається, але місце тримає. */
        const val LINK_STRIP_DP = 64
        /** Як виглядатиме віджет (debug/CI: знімки емулятора; на домашньому екрані емулятора віджет не поставиш). */
        const val DLG_WIDGETS = "widgets"
        const val DLG_FORGET = "forget"
        const val LOG_SHOW_CHARS = 6_000
        const val INSIGHT_DAYS = 14L
        /** Висота рядка «№ + мітка» у плитці комірки — з міткою й без однакова (T19). */
        const val HEAD_DP = 24
        /** T30: значення плитки підсумків — не дрібніше (далі вже «…»). */
        const val SUMMARY_MIN_DP = 12
        /** T31: другорядна частина рядка відключення — не дрібніше (далі «…»). */
        const val OUTAGE_MIN_DP = 10
        const val INSIGHT_RELOAD_MS = 5 * 60_000L
    }
}

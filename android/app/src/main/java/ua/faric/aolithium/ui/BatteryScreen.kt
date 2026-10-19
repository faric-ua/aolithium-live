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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ua.faric.aolithium.ble.BatteryLink
import ua.faric.aolithium.ble.BleScanner
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.ble.DemoLink
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.CellTrend
import ua.faric.aolithium.core.ChargerCheck
import ua.faric.aolithium.core.DailyMin
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.Glossary
import ua.faric.aolithium.core.Health
import ua.faric.aolithium.core.HistoryMath
import ua.faric.aolithium.core.HistoryPoint
import ua.faric.aolithium.core.Laggard
import ua.faric.aolithium.core.Outages
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.SteadyCurrent
import ua.faric.aolithium.core.TimeLeft
import ua.faric.aolithium.core.Zones
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.HistoryCsv
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.HistoryStore
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.data.Marks

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
    enum class Tab(val label: String, val icon: LineIcon) {
        NOW("Зараз", LineIcon.GAUGE),
        CELLS("Комірки", LineIcon.CELLS),
        CHARTS("Графіки", LineIcon.CHART),
        BOARD("Плата", LineIcon.CHIP),
        MORE("Ще", LineIcon.DOTS),
    }

    enum class Range(val label: String, val ms: Long) {
        M15("15 хв", 15 * 60_000L),
        H1("1 год", 3_600_000L),
        H6("6 год", 6 * 3_600_000L),
        D1("24 год", 86_400_000L),
        D7("7 днів", 7 * 86_400_000L),
    }

    private val batteries = Batteries(host)
    private val bg = BgSettings(host)
    private val history = HistoryStore.get(host)
    private val demo = DemoLink.sceneOf(address)
    private var link: BatteryLink? = null
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
    /** Хто зараз «найвища» / «найнижча» — липкі між оновленнями (T19, відео faric 19.10 13:57). */
    private var hiCell: Int? = null
    private var loCell: Int? = null
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
    private val updated = ui.muted("", 12.5f)
    override val view: View

    private val ticker = object : Runnable {
        override fun run() {
            renderUpdated()
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
        get() = demo?.let { "Демо: ${it.title}" }
            ?: batteries.find(address)?.title
            ?: Batteries.scanNames[address]
            ?: BmsMatch.shortAddress(address)

    // ---------- життєвий цикл ----------

    override fun onStart() {
        val l = LinkHolder.acquire(host, address)
        link = l
        l.listener = this
        status = l.lastStatus
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
            key == DLG_FORGET -> showForget()
            key == DLG_CLEAR -> showClearHistory()
            key.startsWith(DLG_INFO) -> showInfo(key.removePrefix(DLG_INFO))
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
        this.status = status
        renderHead()
        if (tab != Tab.CHARTS) render()
    }

    override fun onSnapshot(snapshot: BatterySnapshot, atMs: Long) {
        this.snapshot = snapshot
        snapshotAt = atMs
        feed(snapshot, atMs)
        if (!remembered && demo == null) {
            remembered = true
            batteries.remember(address, Batteries.scanNames[address] ?: snapshot.model, BmsMatch.Kind.JBD)
        }
        history.record(address, snapshot, atMs)
        lastStore.save(address, snapshot, atMs)
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
        if (tab != Tab.MORE && tab != Tab.CHARTS) {
            when {
                data != Data.LIVE -> body.addView(offlineCard(), ui.matchWrap())
                status.phase != LinkStatus.Phase.LIVE -> body.addView(statusCard(), ui.matchWrap())
            }
        }
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

    /**
     * Зв'язку зараз нема: що відбувається з підключенням + чиї числа нижче (останні від … / прочерки).
     * Усі вкладки при цьому доступні.
     */
    private fun offlineCard(): View {
        val failed = status.phase == LinkStatus.Phase.FAILED || status.phase == LinkStatus.Phase.NO_ANSWER
        val card = when {
            failed -> ui.card(fill = Palette.dangerFill, stroke = Palette.danger)
            status.phase == LinkStatus.Phase.LOST -> ui.card(fill = Palette.warningFill, stroke = Palette.warning)
            else -> ui.card()
        }
        card.addView(labelRow("Зв'язок", LineIcon.BLUETOOTH, "onelink"))
        card.addView(ui.text(status.phase.title, 17f, Palette.text, medium = true))
        val msg = status.message.ifBlank {
            when (status.phase) {
                LinkStatus.Phase.CONNECTING -> "Підключаюсь до батареї. Зазвичай 2–5 секунд, якщо вона поруч (1–3 м)."
                LinkStatus.Phase.READING -> "Підключились, читаю дані плати…"
                else -> ""
            }
        }
        if (msg.isNotBlank()) card.addView(ui.muted(msg, 14f))
        val st = stale
        card.addView(ui.text(
            if (st != null) "Нижче — останні дані від ${stamp(st.second)} (${Fmt.ago(System.currentTimeMillis() - st.second)}). Наживо — коли з'явиться зв'язок."
            else "З цією батареєю ще не було зв'язку — замість чисел прочерки. Усі вкладки можна дивитись.",
            14f, Palette.warning,
        ))
        if (failed || status.phase == LinkStatus.Phase.LOST || status.phase == LinkStatus.Phase.IDLE) card.addView(retryRow(), ui.matchWrap())
        return card
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
        val row = FlowLayout(host, ui.dp(8), ui.dp(6))
        row.addView(ui.pill(status.phase.title, tone(status.phase)))
        (updated.parent as? ViewGroup)?.removeView(updated)
        row.addView(updated)
        head.addView(row, ui.matchWrap())
        renderUpdated()
    }

    private fun renderUpdated() {
        updated.text = when {
            snapshotAt != 0L -> "оновлено " + Fmt.ago(System.currentTimeMillis() - snapshotAt)
            stale != null -> "останні дані від " + stamp(stale.second)
            else -> "даних ще нема"
        }
    }

    private fun tone(p: LinkStatus.Phase) = when (p) {
        LinkStatus.Phase.LIVE -> Ui.Tone.GOOD
        LinkStatus.Phase.CONNECTING, LinkStatus.Phase.READING -> Ui.Tone.INFO
        LinkStatus.Phase.LOST, LinkStatus.Phase.NO_ANSWER -> Ui.Tone.WARN
        LinkStatus.Phase.FAILED -> Ui.Tone.BAD
        LinkStatus.Phase.IDLE -> Ui.Tone.NEUTRAL
    }

    // ---------- «Зараз» ----------

    private fun renderNow(s: BatterySnapshot) {
        val gaugeCard = ui.card(6)
        gaugeCard.addView(labelRow("Заряд", LineIcon.BATTERY, "soc"))
        gaugeCard.addView(GaugeView(host).apply {
            soc = s.soc
            current = s.current
            power = s.power
            voltage = s.voltage
            showAmps = marks.gaugeAmps
            caption = "${Fmt.num(s.remainAh, 1)} з ${Fmt.num(s.fullAh, 1)} А·год"
            noData = data == Data.NONE
            setOnClickListener { showInfo("zones") }
        }, ui.matchWrap())
        // Перемикач великого числа: вати чи ампери (faric 19.10 03:02: «щоб можна було і те дивитися, і те»).
        gaugeCard.addView(ui.grid(listOf(
            ui.choice("Вати", !marks.gaugeAmps) { if (marks.gaugeAmps) { marks.gaugeAmps = false; render() } },
            ui.choice("Ампери", marks.gaugeAmps) { if (!marks.gaugeAmps) { marks.gaugeAmps = true; render() } },
        )), ui.matchWrap())
        if (data == Data.NONE) {
            gaugeCard.addView(ui.text("Немає даних", 15f, Palette.muted, medium = true).apply { gravity = Gravity.CENTER }, ui.matchWrap())
            gaugeCard.addView(ui.muted("Зони на ободі: червона 0–10 %, помаранчева 10–20 %, зелена 20–90 %, помаранчева 90–100 %. Торкнись — докладно.", 13f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        } else {
            val zone = Zones.of(s.soc)
            gaugeCard.addView(ui.text(zone.title, 15f, GaugeView.toneColor(zone.tone), medium = true).apply { gravity = Gravity.CENTER }, ui.matchWrap())
            gaugeCard.addView(ui.muted(zone.advice, 13f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        }

        val now = ui.card(8)
        now.addView(labelRow("Зараз", LineIcon.PULSE, null))
        now.addView(fact("Напруга", Fmt.volts(s.voltage), "voltage"))
        // Стрілки як на спідометрі: віддає — ▲ червоне (енергія йде з батареї), заряджається — ▼ зелене.
        val (curText, curColor) = when (s.flow) {
            BatterySnapshot.Flow.CHARGING -> Fmt.amps(s.current) + " ▼ бере" to Palette.success
            BatterySnapshot.Flow.DISCHARGING -> Fmt.amps(-s.current) + " ▲ віддає" to Palette.danger
            BatterySnapshot.Flow.IDLE -> Fmt.amps(s.current) to Palette.text
        }
        now.addView(fact("Струм", curText, "current", curColor))
        now.addView(fact("Потужність", Fmt.watts(kotlin.math.abs(s.power)), "power", curColor))
        if (s.temperatures.isNotEmpty()) {
            val hot = s.temperatures.max()
            now.addView(fact(if (s.temperatures.size == 1) "Температура" else "Температура (макс.)", Fmt.celsius(hot), "temp", tempColor(hot)))
        } else if (data == Data.NONE) {
            now.addView(fact("Температура", DASH, "temp"))
        }
        s.deltaMv?.let { d -> now.addView(fact("Різниця комірок", "${Fmt.millivolts(d)} — ${s.deltaVerdict!!.title}", "delta", verdictColor(s))) }
            ?: if (data == Data.NONE) now.addView(fact("Різниця комірок", DASH, "delta")) else Unit
        now.addView(fact("Цикли", s.cycles.toString(), "cycles"))

        val state = ui.card()
        state.addView(labelRow("Стан плати", LineIcon.SHIELD, "protState"))
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        if (data == Data.NONE) {
            row.addView(pillInfo("Заряд: $DASH", Ui.Tone.NEUTRAL, "chargeSw"))
            row.addView(pillInfo("Розряд: $DASH", Ui.Tone.NEUTRAL, "dischargeSw"))
            row.addView(pillInfo("Балансування: $DASH", Ui.Tone.NEUTRAL, "balance"))
            row.addView(pillInfo("Захист: $DASH", Ui.Tone.NEUTRAL, "protState"))
            state.addView(row, ui.matchWrap())
            columns(listOf(gaugeCard), listOf(now, state))
            return
        }
        row.addView(pillInfo(if (s.chargeOn) "Заряд: дозволено" else "Заряд: вимкнено", if (s.chargeOn) Ui.Tone.GOOD else Ui.Tone.BAD, "chargeSw"))
        row.addView(pillInfo(if (s.dischargeOn) "Розряд: дозволено" else "Розряд: вимкнено", if (s.dischargeOn) Ui.Tone.GOOD else Ui.Tone.BAD, "dischargeSw"))
        row.addView(
            if (s.balancingCells.isEmpty()) pillInfo("Балансування: ні", Ui.Tone.NEUTRAL, "balance")
            else pillInfo("Балансування: " + s.balancingCells.joinToString(", ") { "№$it" }, Ui.Tone.INFO, "balance"),
        )
        row.addView(pillInfo(if (s.hasProblem) "Захист спрацював" else "Захист: усе гаразд", if (s.hasProblem) Ui.Tone.BAD else Ui.Tone.GOOD, "protState"))
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
            card.addView(labelRow("Вистачить на", LineIcon.BATTERY, "time"))
            card.addView(numbers(ui.text("≈ " + Fmt.duration(d.totalH), 26f, Palette.text, medium = true)))
            val to20 = d.toThreshold.first { it.first == 20 }.second
            card.addView(ui.text(
                if (to20 != null) "(до 20 % — ${Fmt.duration(to20)})" else "(уже нижче 20 % — час заряджати)",
                15f, Palette.danger, medium = true,
            ))
            card.addView(socStrip(s.soc, charging = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)))
            val steps = d.toThreshold.map { (p, h) ->
                val (fill, color, note) = when (p) {
                    20 -> Triple(Palette.selectedFill, Palette.success, "спокійно")
                    10 -> Triple(Palette.warningFill, Palette.warning, "час заряджати")
                    else -> Triple(Palette.dangerFill, Palette.danger, "не варто нижче")
                }
                step("до $p %", h?.let { Fmt.duration(it) } ?: "уже нижче", note, fill, color)
            }
            card.addView(ui.grid(steps, perRow = 3, gapDp = 6), ui.matchWrap())
            card.addView(ui.muted("Рахую з залишку (${Fmt.ampHours(s.remainAh)}) і середнього струму ${Fmt.amps(-amps)} за 2 хв. " +
                "Число перераховується, лише коли струм стабільно змінився більше ніж на 10 %.", 12.5f))
            return card
        }
        TimeLeft.charge(s.remainAh, s.fullAh, amps)?.let { c ->
            card.addView(labelRow("До повної", LineIcon.BATTERY, "time"))
            card.addView(numbers(ui.text(if (c.toFullH <= 0.0) "уже повна" else "≈ " + Fmt.duration(c.toFullH), 26f, Palette.text, medium = true)))
            card.addView(ui.text("(до 100 % — заряд зупинить сама плата)", 15f, Palette.success, medium = true))
            card.addView(socStrip(s.soc, charging = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)))
            val perH = c.percentPerHour
            card.addView(ui.grid(listOf(
                step("за 1 год такого заряду", "+${Fmt.num(minOf(perH, 100.0 - s.soc).coerceAtLeast(0.0), 0)} %",
                    "≈ ${Fmt.num(amps, 1)} А·год", Palette.selectedFill, Palette.success),
                step("за 2 год", "+${Fmt.num(minOf(perH * 2, 100.0 - s.soc).coerceAtLeast(0.0), 0)} %",
                    "≈ ${Fmt.num(amps * 2, 1)} А·год", Palette.selectedFill, Palette.success),
            ), perRow = 2, gapDp = 6), ui.matchWrap())
            card.addView(ui.muted("Зручно для генератора: видно, скільки дасть кожна година. Наприкінці заряду струм сам спадає — час уточниться.", 12.5f))
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

    // ---------- «Комірки» ----------

    private fun renderCells(s: BatterySnapshot) {
        val cards = ArrayList<View>()
        if (s.cellsMv.isEmpty()) {
            val card = ui.card(8)
            card.addView(labelRow("Комірки", LineIcon.CELLS, "cells"))
            card.addView(ui.muted("Напруга кожної комірки з'явиться після підключення. У батареї Aolithium їх 4.", 14f))
            for (i in 1..4) card.addView(fact("Комірка $i", DASH, "cells"))
            card.addView(fact("Різниця", DASH, "delta"))
            cards += card
        } else {
            val card = ui.card(8)
            card.addView(labelRow("Комірки: ${s.cellsMv.size}", LineIcon.CELLS, "cellTrend"))
            if (data == Data.LIVE && s.flow != BatterySnapshot.Flow.IDLE) {
                val pill = if (s.flow == BatterySnapshot.Flow.CHARGING) ui.pill("▼ Заряджається · ${Fmt.amps(s.current)}", Ui.Tone.GOOD)
                else ui.pill("▲ Віддає · ${Fmt.amps(-s.current)}", Ui.Tone.BAD)
                card.addView(FlowLayout(host, ui.dp(8), ui.dp(6)).apply { addView(pill) }, ui.matchWrap())
                card.addView(ui.muted("Комірки з'єднані ланцюжком — струм через усі однаковий. А напруга росте по-різному: дивись «за 1 хв».", 13f))
            }
            val dev = CellTrend.fromAverage(s.cellsMv)
            val trend = if (data == Data.LIVE) CellTrend.perMinute(cellSamples.toList()) else null
            val (hi, lo) = CellTrend.extremes(s.cellsMv, hiCell, loCell)
            hiCell = hi
            loCell = lo
            // Якщо хоч одна вирівнюється — місце під мітку є в кожній плитці, щоб усі були однієї висоти.
            val anyBalancing = s.cellsMv.indices.any { (it + 1) in s.balancingCells }
            val tiles = s.cellsMv.mapIndexed { i, mv ->
                cellTile(i, mv, dev[i], trend?.getOrNull(i), isHi = i == hi, isLo = i == lo, balancing = (i + 1) in s.balancingCells, anyBalancing = anyBalancing)
            }
            card.addView(ui.grid(tiles, perRow = if (ui.twoPane && tiles.size > 2) 4 else 2), ui.matchWrap())
            s.deltaMv?.let { d -> card.addView(fact("Різниця", "${Fmt.millivolts(d)} — ${s.deltaVerdict!!.title}", "delta", verdictColor(s))) }
            s.averageCellV?.let { card.addView(fact("Середня", Fmt.num(it, 3) + " В", "avgcell")) }
            card.addView(ui.muted("Рамка: блакитна — найвища, помаранчева — найнижча; фіолетова мітка — вирівнюється. " +
                "За 1 хв: ▲ зелене — напруга росте, ▼ червоне — падає. Смужка — від 2,5 В (порожня) до 3,65 В (повна).", 12f))
            cards += card

            val bal = ui.card(8)
            bal.addView(labelRow("Як плата вирівнює комірки", LineIcon.CELLS, "balance"))
            if (s.balancingCells.isEmpty()) {
                bal.addView(ui.text("Зараз не вирівнює", 15f, Palette.text, medium = true))
                bal.addView(ui.muted("Плата Aolithium вмикає вирівнювання наприкінці заряду, коли комірки розходяться.", 13.5f))
            } else {
                bal.addView(ui.text("Зараз вирівнює: " + s.balancingCells.joinToString(", ") { "№$it" }, 15f, Palette.violet, medium = true))
                bal.addView(ui.muted("Плата злегка розряджає цю комірку (у тепло, через резистор), поки інші її доганяють.", 13.5f))
            }
            bal.addView(ui.muted("Це пасивне вирівнювання: скільки ампер — плата JBD не повідомляє, лише «вирівнює / ні». " +
                "У Logic Power — активний балансир: перекачує енергію з найвищої комірки в найнижчу і показує струм (буде у версії 0.4).", 12.5f))
            cards += bal

            val hour = ui.card(8)
            hour.addView(labelRow("Різниця комірок за 1 год", LineIcon.CHART, "delta"))
            val hp = hourPts
            if (hp.isNullOrEmpty()) {
                hour.addView(ui.muted(if (hp == null) "Завантажую…" else "Ще немає записів за останню годину.", 13f))
            } else {
                val to = System.currentTimeMillis()
                val from = to - 3_600_000L
                val chart = ChartView(host).apply {
                    set(listOf(ChartView.Series("Різниця", Palette.accent, HistoryMath.bucket(hp, from, to, 120) { it.deltaMv?.toDouble() })),
                        from, to, "мВ", 0, min = 0.0, gapMs = HistoryMath.GAP_MS)
                }
                hour.addView(chart, ui.matchWrap())
                val first = hp.firstNotNullOfOrNull { it.deltaMv }
                val last = hp.lastOrNull()?.deltaMv
                if (first != null && last != null) hour.addView(ui.muted("Годину тому ${Fmt.millivolts(first)}, зараз ${Fmt.millivolts(last)}. " +
                    "Наприкінці заряду різниця трохи росте — це нормально. До 30 мВ — рівно.", 12.5f))
            }
            cards += hour
        }
        val temps = ui.card(8)
        temps.addView(labelRow("Температура", LineIcon.THERMO, "temp"))
        if (s.temperatures.isEmpty()) temps.addView(
            if (data == Data.NONE) fact("Датчики", DASH, "temp") else ui.muted("Плата не повідомила жодного датчика.", 14f),
        )
        s.temperatures.forEachIndexed { i, t ->
            temps.addView(fact("Датчик ${i + 1}", Fmt.celsius(t), "temp", tempColor(t)))
        }
        temps.addView(ui.muted("Найкраще 15–35 °C. Нижче 0 °C плата не дасть заряджати.", 12.5f))
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
        head.addView(ui.text("№${i + 1}", 13f, Palette.muted, medium = true).apply { maxLines = 1 })
        val mark = when {
            isHi -> ui.pill("найвища", Ui.Tone.INFO)
            isLo -> ui.pill("найнижча", Ui.Tone.WARN)
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
        tile.addView(miniRow("від\nсередньої", (if (dev > 0) "+" else if (dev < 0) "−" else "") + "${kotlin.math.abs(dev)} мВ", Palette.text, lines = 2))
        val shownPerMin = perMin?.let { CellTrend.shown(it) }
        val (t, c) = when {
            shownPerMin == null -> (if (data == Data.LIVE) "рахую…" else "—") to Palette.muted
            shownPerMin > 0 -> "▲ +$shownPerMin мВ" to Palette.success
            shownPerMin < 0 -> "▼ −${-shownPerMin} мВ" to Palette.danger
            else -> "без змін" to Palette.muted
        }
        tile.addView(miniRow("за 1 хв", t, c))
        if (anyBalancing) {
            val pill = ui.pill("≈ вирівнюється", Ui.Tone.VIOLET).apply {
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
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = numbers(ui.text(Ui.keepUnits(value), 12.5f, color, medium = true))
        v.maxLines = 1
        v.gravity = Gravity.END
        v.minWidth = kotlin.math.ceil(listOf(Ui.keepUnits("▼ −20 мВ"), "без змін", "рахую…").maxOf { v.paint.measureText(it) }).toInt()
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
            body.addView(ui.muted("Завантажую історію…", 14f).apply { minHeight = ui.dp(ChartView.HEIGHT_DP) }, ui.matchWrap())
            return
        }
        val to = System.currentTimeMillis()
        val from = to - shown.ms
        val pts = points
        val stats = HistoryMath.stats(pts)

        val sum = ui.card(8)
        sum.addView(labelRow(if (shown != range) "За ${shown.label} · оновлюю…" else "За ${shown.label}", LineIcon.LIST, "energy"))
        if (pts.isEmpty()) {
            sum.addView(ui.muted("Ще немає записів. Історія пишеться раз на 10 с, поки застосунок відкритий і на зв'язку, а з увімкненим «Запис у фоні» («Ще») — раз на ~5 хв і коли закритий.", 14f))
        } else {
            sum.addView(fact("Заряджено", "${Fmt.num(stats.chargedWh, 0)} Вт·год · ${Fmt.num(stats.chargedAh, 1)} А·год", "energy", Palette.success, known = true))
            sum.addView(fact("Віддано", "${Fmt.num(stats.dischargedWh, 0)} Вт·год · ${Fmt.num(stats.dischargedAh, 1)} А·год", "energy", Palette.danger, known = true))
            if (stats.socMin != null) sum.addView(fact("Заряд", "${stats.socMin}–${stats.socMax} %", "soc", known = true))
            sum.addView(fact("Найбільший струм", "заряд ${Fmt.amps(stats.maxChargeA)} · розряд ${Fmt.amps(stats.maxDischargeA)}", "current", known = true))
            if (stats.tempMin != null) sum.addView(fact("Температура", "${Fmt.celsius(stats.tempMin)} … ${Fmt.celsius(stats.tempMax!!)}", "temp", known = true))
            stats.deltaMaxMv?.let { sum.addView(fact("Найбільша різниця комірок", Fmt.millivolts(it), "delta", known = true)) }
            val share = (stats.coveredMs * 100 / shown.ms).coerceIn(0, 100)
            sum.addView(ui.muted("Записано ${share} % часу (решта — застосунок був закритий і фон вимкнений).", 12.5f))
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
        val socChart = ChartView(host).apply { set(listOf(ser("Заряд", Palette.accent) { it.soc.toDouble() }), from, to, "%", 0, min = 0.0, max = 100.0, gapMs = gap) }
        val curChart = ChartView(host).apply { set(listOf(ser("Струм", Palette.success) { it.current }), from, to, "А", 2, zeroSplit = true, gapMs = gap) }
        val pwrChart = ChartView(host).apply { set(listOf(ser("Потужність", Palette.success) { it.power }), from, to, "Вт", 0, zeroSplit = true, gapMs = gap) }
        val vChart = ChartView(host).apply { set(listOf(ser("Напруга", Palette.accent) { it.voltage }), from, to, "В", 2, gapMs = gap) }
        val tChart = ChartView(host).apply {
            set(listOf(ser("Макс.", Palette.warning) { it.tempMax }, ser("Мін.", Palette.accent) { it.tempMin }), from, to, "°C", 1, gapMs = gap)
        }
        val dChart = ChartView(host).apply { set(listOf(ser("Різниця", Palette.warning) { it.deltaMv?.toDouble() }), from, to, "мВ", 0, min = 0.0, gapMs = gap) }
        // Без зеленого й червоного: тут вони означають заряд / розряд.
        val cellColors = listOf(Palette.warning, Palette.accent, Palette.violet, Palette.text)
        val cellsN = pts.lastOrNull()?.cellsMv?.size ?: 0
        val cChart = ChartView(host).apply {
            set((0 until cellsN).map { i -> ser("№${i + 1}", cellColors[i % cellColors.size]) { p -> p.cellsMv.getOrNull(i)?.div(1000.0) } }, from, to, "В", 3, gapMs = gap)
        }
        val left = listOf(sum, chartCard("Заряд, %", LineIcon.BATTERY, "soc", socChart), chartCard("Струм, А", LineIcon.BOLT, "current", curChart,
            listOf("заряд" to Palette.success, "розряд" to Palette.danger)), chartCard("Потужність, Вт", LineIcon.BOLT, "power", pwrChart))
        val right = listOf(chartCard("Напруга, В", LineIcon.PULSE, "voltage", vChart), chartCard("Температура, °C", LineIcon.THERMO, "temp", tChart,
            listOf("макс." to Palette.warning, "мін." to Palette.accent)), chartCard("Різниця комірок, мВ", LineIcon.CELLS, "delta", dChart),
            chartCard("Комірки, В", LineIcon.CELLS, "cells", cChart, (0 until cellsN).map { "№${it + 1}" to cellColors[it % cellColors.size] }))
        val ins = insightCards()
        columns(ins.take(2) + left, right + ins.drop(2))
        body.addView(ui.muted("Торкнись графіка й веди пальцем — побачиш значення в будь-який момент.", 12.5f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
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
        oc.addView(labelRow("Відключення світла", LineIcon.BOLT, "outages"))
        if (pts == null) {
            oc.addView(ui.muted("Завантажую історію…", 13f))
            out += oc
            return out
        }
        val outages = Outages.find(pts)
        if (outages.isEmpty()) {
            oc.addView(ui.muted("За ${INSIGHT_DAYS} днів у записаній історії відключень не знайдено. Застосунок помічає їх сам: батарея " +
                "кілька хвилин віддає струм — світла нема; знову заряджається чи стоїть — світло є. Записується, поки застосунок відкритий " +
                "(запис у фоні — наступна версія).", 13f))
        } else {
            val week = outages.filter { it.end > now - 7 * DailyMin.DAY_MS }
            val sumBox = ArrayList<View>()
            sumBox += summaryTile("за 7 днів", "${week.size} " + Fmt.plural(week.size, "відключення", "відключення", "відключень"))
            sumBox += summaryTile("без світла", Fmt.duration(week.sumOf { it.durationMs } / 3_600_000.0))
            sumBox += summaryTile("віддала", if (week.sumOf { it.wh } >= 1000) Fmt.num(week.sumOf { it.wh } / 1000, 1) + " кВт·год" else Fmt.num(week.sumOf { it.wh }, 0) + " Вт·год")
            sumBox += summaryTile("найнижче", (week.minOfOrNull { it.socTo }?.let { "$it %" } ?: "—"))
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
                val title = if (ongoing) "Зараз — світла нема, з ${hm.format(java.util.Date(o.start))}"
                else "${day.format(java.util.Date(o.start))} ${hm.format(java.util.Date(o.start))} → ${hm.format(java.util.Date(o.end))}"
                box.addView(miniRow(title, Fmt.duration((if (ongoing) now - o.start else o.durationMs) / 3_600_000.0), if (ongoing) Palette.danger else Palette.text).also {
                    ((it as LinearLayout).getChildAt(0) as TextView).apply { setTextColor(if (ongoing) Palette.danger else Palette.text); textSize = 14f }
                })
                box.addView(miniRow("${o.socFrom} % → ${o.socTo} %", "${Fmt.num(o.ah, 1)} А·год ≈ ${Fmt.num(o.wh, 0)} Вт·год", Palette.muted))
                box.addView(miniRow("в середньому ${Fmt.watts(o.avgW)}", "пік ${Fmt.watts(o.peakW)}", Palette.muted))
                oc.addView(box, ui.matchWrap())
            }
            oc.addView(ui.muted("Видно лише години, коли застосунок записував (зараз — поки відкритий).", 12.5f))
        }
        out += oc

        // ---- найнижчий заряд по днях ----
        val days = DailyMin.of(pts, java.util.TimeZone.getDefault().getOffset(now).toLong()).takeLast(10)
        val dc = ui.card(8)
        dc.addView(labelRow("Найнижчий заряд по днях", LineIcon.CHART, "dailyMin"))
        if (days.isEmpty()) {
            dc.addView(ui.muted("Ще немає записаних днів.", 13f))
        } else {
            val gen = marks.generatorDays(address)
            val keys = days.map { dayKey.format(java.util.Date(it.dayStart + 12 * 3_600_000L)) }
            val bars = DayBarsView(host).apply {
                set(days.mapIndexed { i, d -> DayBarsView.Bar(day.format(java.util.Date(d.dayStart + 12 * 3_600_000L)).take(2), d.minSoc, keys[i] in gen) })
                onDay = { i ->
                    val on = marks.toggleGenerator(address, keys[i])
                    host.toast(day.format(java.util.Date(days[i].dayStart + 12 * 3_600_000L)) + if (on) " — позначено: генератор" else " — позначку знято")
                    render()
                }
            }
            dc.addView(bars, ui.matchWrap())
            dc.addView(ui.muted("Торкнись дня — позначити «Г» (працював генератор). Для батареї світло й генератор однакові, тож сам застосунок їх не розрізнить.", 12.5f))
        }
        out += dc

        // ---- хто відстає ----
        Laggard.find(pts)?.let { r ->
            val lc = ui.card(8)
            lc.addView(labelRow("Хто відстає наприкінці заряду", LineIcon.CELLS, "laggard"))
            r.leader?.let { lead ->
                lc.addView(fact("Найчастіше найнижча", "№${lead + 1} · ${r.lowestCount[lead]} з ${r.episodes}", "laggard", Palette.warning, known = true))
            }
            lc.addView(fact("Різниця в кінці заряду", "${r.deltas.min()}–${r.deltas.max()} мВ", "laggard",
                if (r.deltas.max() >= BatterySnapshot.Verdict.WATCH_FROM_MV) Palette.warning else Palette.success, known = true))
            lc.addView(ui.muted("Це не поломка: одна комірка вгорі трохи «менша», плата її підтягує. Тривожно, якщо різниця в кінці заряду росте від тижня до тижня.", 12.5f))
            out += lc
        }

        // ---- зарядний ----
        ChargerCheck.of(pts)?.let { c ->
            val cc = ui.card(8)
            cc.addView(labelRow("Зарядний", LineIcon.BOLT, "charger"))
            val n = pts.lastOrNull()?.cellsMv?.size?.takeIf { it > 0 } ?: 4
            val perCell = Fmt.num(c.maxV / n, 2) + " В"
            val color = if (c.verdict == ChargerCheck.Verdict.OK) Palette.success else Palette.warning
            cc.addView(fact("Найвища напруга на заряді", "${Fmt.volts(c.maxV)} · ${perCell} на комірку", "charger", color, known = true))
            cc.addView(fact("Оцінка", c.verdict.title, "charger", color, known = true))
            cc.addView(ui.muted("За ${INSIGHT_DAYS} днів записаної історії. Для 4 комірок LiFePO4 норма — 13,8–14,6 В (3,45–3,65 В на комірку). Орієнтир, не діагноз.", 12.5f))
            out += cc
        }
        return out
    }

    private fun summaryTile(top: String, value: String): View = ui.column(2).apply {
        background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
        setPadding(ui.dp(11), ui.dp(9), ui.dp(11), ui.dp(9))
        addView(ui.text(top, 12f, Palette.muted))
        addView(numbers(ui.text(Ui.keepUnits(value), 17f, Palette.text, medium = true)))
    }

    // ---------- «Плата» ----------

    private fun renderBoard(s: BatterySnapshot) {
        val health = ui.card(8)
        health.addView(labelRow("Здоров'я", LineIcon.BATTERY, "soh"))
        val rated = Health.ratedAh(s.model)
        rated?.let { health.addView(fact("На етикетці", "$it А·год", "soh")) }
        health.addView(fact("Повна (знає плата)", Fmt.ampHours(s.fullAh), "full"))
        Health.sohPercent(s.fullAh, rated)?.let { health.addView(fact("Від етикетки", "$it %", "soh", if (it >= 80) Palette.success else Palette.warning)) }
        health.addView(fact("Залишок", Fmt.ampHours(s.remainAh), "remain"))
        health.addView(fact("Цикли", s.cycles.toString(), "cycles"))
        health.addView(fact("Енергія повної", "≈ " + Fmt.num(s.fullAh * 12.8 / 1000, 2) + " кВт·год", "energy"))

        val board = ui.card(8)
        board.addView(labelRow("Плата", LineIcon.CHIP, "board"))
        board.addView(fact("Тип", "JBD (Jiabaida)", "board", known = true))
        s.model?.let { board.addView(fact("Назва плати", it, "model")) } ?: if (data == Data.NONE) board.addView(fact("Назва плати", DASH, "model")) else Unit
        s.firmware?.let { board.addView(fact("Версія програми", it, "firmware")) }
        s.made?.let { board.addView(fact("Дата виготовлення", it.toString(), "made")) }
        board.addView(fact("Комірок", s.cellCount.toString(), "cells"))
        board.addView(fact("Датчиків температури", s.temperatures.size.toString(), "temp"))
        if (demo == null) {
            board.addView(fact("Адреса", BmsMatch.shortAddress(address), "onelink"))
            BleScanner.lastRssi[address]?.let { board.addView(fact("Сигнал (у пошуку)", Signal.describe(it), "signal", if (Signal.tooFar(it)) Palette.warning else Palette.text)) }
        }

        val right = ArrayList<View>()
        if (s.hasProblem) right += problemCard(s)
        if (s.errorCounts == null) {
            val c = ui.card(8)
            c.addView(labelRow("Захисти за весь час", LineIcon.LIST, "counters"))
            c.addView(ui.muted("Скільки разів плата вмикала кожен захист — з'явиться після підключення.", 13f))
            right += c
        }
        s.errorCounts?.let { counts ->
            val c = ui.card(8)
            c.addView(labelRow("Захисти за весь час", LineIcon.LIST, "counters"))
            c.addView(ui.muted("Скільки разів плата вмикала кожен захист. Торкнись рядка — що це і що робити.", 12.5f))
            for ((name, n) in counts) c.addView(fact(name, n.toString(), Glossary.forCounter(name), if (n > 0) Palette.warning else Palette.muted))
            val over = counts.firstOrNull { it.first == "Перезаряд комірки" }?.second ?: 0
            Health.overchargeAdvice(over)?.let { advice ->
                c.addView(ui.card(6, fill = Palette.warningFill, stroke = Palette.warning).apply {
                    addView(ui.text("Порада", 15f, Palette.warning, medium = true))
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
        actions.addView(labelRow("Дії", LineIcon.SLIDERS, null))
        val buttons = ArrayList<View>()
        if (demo == null) buttons += ui.iconButton(LineIcon.PEN, "Перейменувати", Ui.Kind.NORMAL) { showRename() }
        buttons += ui.iconButton(LineIcon.REPEAT, "Підключити ще раз", Ui.Kind.NORMAL) { reconnect() }
        buttons += ui.button("Журнал зв'язку", Ui.Kind.NORMAL) { showLog() }
        if (demo == null && batteries.find(address) != null) buttons += ui.button("Прибрати зі списку", Ui.Kind.QUIET) { showForget() }
        actions.addView(ui.grid(buttons), ui.matchWrap())
        actions.addView(ui.muted("Застосунок лише читає плату — нічого в ній не змінює. Поки він підключений, Aolithium на іншому телефоні не підключиться: сховай цей застосунок — через 5 с зв'язок звільниться.", 12.5f))

        val hist = ui.card(8)
        hist.addView(labelRow("Історія", LineIcon.CHART, "history"))
        val (days, bytes) = historyBytes ?: history.size(address).also { historyBytes = it }
        hist.addView(fact("Записано", if (demo != null) "демо — не пишеться" else "$days дн. · ${Fmt.num(bytes / 1024.0, 0)} КБ", "history", known = true))
        hist.addView(fact("Як часто", if (demo == null && bg.isOn(address)) "раз на 10 с відкрито · ~5 хв у фоні" else "раз на 10 с, поки відкрито", "history", known = true))
        hist.addView(fact("Скільки зберігається", "${HistoryStore.KEEP_DAYS} днів", "history", known = true))
        if (demo == null && days > 0) {
            hist.addView(ui.iconButton(LineIcon.LIST, "Зберегти CSV (таблиця)", Ui.Kind.NORMAL) { saveCsv() }, ui.matchWrap())
            hist.addView(ui.button("Стерти історію цієї батареї", Ui.Kind.DANGER) { showClearHistory() }, ui.matchWrap())
        }

        val dict = ui.card(6)
        dict.addView(labelRow("Довідник: що означає кожен показник", LineIcon.BOOK, null))
        for ((g, entries) in Glossary.byGroup()) {
            dict.addView(ui.text(g.title, 14f, Palette.accent, medium = true).apply { setPadding(0, ui.dp(8), 0, 0) })
            for (en in entries) dict.addView(infoRow(en.title, en.id), ui.matchWrap())
        }

        val help = ui.card()
        help.addView(labelRow("Довідка", LineIcon.HELP, null))
        help.addView(ui.muted("Як підключити, чому не видно батареї, демо без батареї.", 13f))
        help.addView(ui.iconButton(LineIcon.HELP, "Відкрити довідку", Ui.Kind.NORMAL) { host.openHelp() }, ui.matchWrap())

        columns(listOf(backgroundCard(), actions, hist, help), listOf(dict))
    }

    /**
     * «Запис у фоні» (v0.2.5, T3): перемикач для цієї батареї, останній запис, і що ще заважає
     * (сповіщення заборонені, Android обмежує батарею для застосунку).
     */
    private fun backgroundCard(): View {
        val on = demo == null && bg.isOn(address)
        val card = if (on) ui.card(8, fill = Palette.accentFill, stroke = Palette.accent) else ui.card(8)
        card.addView(labelRow("Запис у фоні", LineIcon.REPEAT, "background"))
        if (demo != null) {
            card.addView(ui.muted("У демо не працює — лише зі справжньою батареєю.", 13f))
            return card
        }
        card.addView(ui.muted("Раз на ~5 хв, навіть коли застосунок закритий: коротко підключається, записує показники й відпускає батарею. Так видно відключення світла, що були без тебе.", 13f))
        card.addView(ui.grid(listOf(
            ui.choice("Увімкнено", on) { setBackground(true) },
            ui.choice("Вимкнено", !on) { setBackground(false) },
        )), ui.matchWrap())
        if (on) {
            card.addView(fact("Останній запис", Background.status(bg.result(address)), "background", known = true))
            if (!RecordService.running) card.addView(ui.muted("Служба запису зараз не працює — Android її зупинив. Відкрий застосунок ще раз або вимкни й увімкни.", 13f))
            if (BtAccess.missingPermissions(host).isNotEmpty()) {
                card.addView(ui.muted("Немає дозволу на Bluetooth — без нього фон не прочитає батарею.", 13f))
                card.addView(ui.button("Дати дозвіл", Ui.Kind.PRIMARY) { host.requestAccess() }, ui.matchWrap())
            } else if (!host.notificationsAllowed) {
                card.addView(ui.muted("Сповіщення заборонені: запис працює, але в шторці його не видно, і вимкнути можна лише тут.", 13f))
                card.addView(ui.button("Дозволити сповіщення", Ui.Kind.NORMAL) { host.requestNotifications() }, ui.matchWrap())
            }
            if (!host.batteryUnrestricted) {
                card.addView(ui.muted("Android може приспати запис, щоб берегти батарею телефона. Щоб писав рівно — дозволь «без обмежень».", 13f))
                card.addView(ui.button("Не обмежувати у фоні", Ui.Kind.NORMAL) { host.askUnrestrictedBattery() }, ui.matchWrap())
            }
        }
        return card
    }

    private fun setBackground(on: Boolean) {
        if (on == bg.isOn(address)) return
        bg.set(address, on)
        if (on && batteries.find(address) == null) batteries.remember(address, Batteries.scanNames[address], BmsMatch.Kind.JBD)
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

    private fun waitingCard(): View {
        val failed = status.phase == LinkStatus.Phase.FAILED || status.phase == LinkStatus.Phase.NO_ANSWER
        val card = if (failed) ui.card(fill = Palette.dangerFill, stroke = Palette.danger) else ui.card()
        card.addView(labelRow("Зв'язок", LineIcon.BLUETOOTH, "onelink"))
        card.addView(ui.text(status.phase.title, 18f, Palette.text, medium = true))
        val msg = status.message.ifBlank {
            when (status.phase) {
                LinkStatus.Phase.CONNECTING -> "Підключаюсь до батареї. Зазвичай 2–5 секунд."
                LinkStatus.Phase.READING -> "Підключились, читаю дані плати…"
                else -> ""
            }
        }
        if (msg.isNotBlank()) card.addView(ui.muted(msg, 14f))
        if (failed || status.phase == LinkStatus.Phase.LOST) card.addView(retryRow(), ui.matchWrap())
        return card
    }

    private fun statusCard(): View {
        val card = ui.card(fill = Palette.warningFill, stroke = Palette.warning)
        card.addView(ui.text(status.phase.title, 16f, Palette.text, medium = true))
        if (status.message.isNotBlank()) card.addView(ui.muted(status.message, 14f))
        card.addView(ui.muted("Нижче — останні отримані дані.", 13f))
        if (status.phase != LinkStatus.Phase.CONNECTING && status.phase != LinkStatus.Phase.READING) card.addView(retryRow(), ui.matchWrap())
        return card
    }

    private fun retryRow(): View = ui.grid(listOf(
        ui.iconButton(LineIcon.REPEAT, "Підключити ще раз", Ui.Kind.PRIMARY) { reconnect() },
        ui.button("Журнал зв'язку", Ui.Kind.QUIET) { showLog() },
    ))

    private fun reconnect() {
        LinkHolder.release(this)
        val l = LinkHolder.restart(host, address)
        link = l
        l.listener = this
        status = l.lastStatus
        render()
    }

    private fun problemCard(s: BatterySnapshot): View {
        val card = ui.card(fill = Palette.dangerFill, stroke = Palette.danger)
        card.addView(ui.label("Що спрацювало", LineIcon.SHIELD))
        for (p in s.protections) {
            card.addView(ui.text(p.title, 16f, Palette.text, medium = true))
            card.addView(ui.muted(p.explain, 14f))
        }
        for (bit in s.unknownProtectionBits) {
            card.addView(ui.text("Невідомий сигнал плати (біт $bit)", 16f, Palette.text, medium = true))
            card.addView(ui.muted("Плата повідомляє те, чого ми ще не знаємо. Надішли Claude скрін цього екрана.", 14f))
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
        contentDescription = "Що це"
        isClickable = true
        background = ui.pressable(ui.rounded(Palette.transparent, radiusDp = 24), 24)
        setOnClickListener { showInfo(id) }
    }

    /** «Назва ············ значення ⓘ» — весь рядок натискається. */
    private fun fact(name: String, value: CharSequence, info: String, color: Int = Palette.text, known: Boolean = false): View {
        val none = data == Data.NONE && !known
        return ui.fact(name, (if (none) DASH else value).toString() + "  $INFO", if (none) Palette.muted else color).apply { clickableInfo(this, info) }
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

    private fun showInfo(id: String) {
        val e = Glossary.get(id) ?: return
        val box = ui.column(10)
        e.aolithium?.let { box.addView(ui.muted("В Aolithium 1.3: «$it»", 13f)) }
        box.addView(section("Що це", e.what))
        if (e.normal != "—") box.addView(section("Норма", e.normal))
        if (e.worry != "—") box.addView(section("Коли звернути увагу", e.worry))
        val dlg = host.overlay.dialog(
            e.title, "",
            actions = listOf("Закрити" to Ui.Kind.QUIET),
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
            "Стерти історію «$title»?",
            "Зникнуть усі записані графіки цієї батареї на цьому телефоні. Саму батарею це не чіпає. Повернути не можна.",
            actions = listOf("Стерти" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
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
            hint = Batteries.scanNames[address] ?: "Напр., «Aolithium вдома»"
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
            "Назва батареї",
            "Як показувати її в «Моїх батареях». Порожньо — назва з Bluetooth.",
            actions = listOf("Зберегти" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    if (batteries.find(address) == null) batteries.remember(address, Batteries.scanNames[address], BmsMatch.Kind.JBD)
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
        val text = LinkLog.text().ifBlank { "Журнал порожній." }
        val body = ui.text(text.takeLast(LOG_SHOW_CHARS), 12f, Palette.muted).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val box = host.overlay.dialog(
            "Журнал зв'язку",
            "Що відправили (→) і що прийшло (←). Можна переслати Claude, якщо щось не так.",
            actions = listOf("Поділитися" to Ui.Kind.PRIMARY, "Закрити" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) host.shareText("Aolithium Live — журнал зв'язку", host.versionLine + "\n" + text)
                host.overlay.dismiss()
            },
            extra = body,
        )
        host.overlay.show(DLG_LOG, box)
    }

    private fun showForget() {
        val box = host.overlay.dialog(
            "Прибрати «$title» зі списку?",
            "Зникне лише з «Моїх батарей» у цьому застосунку. Сама батарея й Aolithium нічого не помітять. Знайти знову — пошуком.",
            actions = listOf("Прибрати" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
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
        const val DLG_FORGET = "forget"
        const val LOG_SHOW_CHARS = 6_000
        const val INSIGHT_DAYS = 14L
        /** Висота рядка «№ + мітка» у плитці комірки — з міткою й без однакова (T19). */
        const val HEAD_DP = 24
        const val INSIGHT_RELOAD_MS = 5 * 60_000L
    }
}

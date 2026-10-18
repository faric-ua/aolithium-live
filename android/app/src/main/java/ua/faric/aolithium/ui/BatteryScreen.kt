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
import ua.faric.aolithium.ble.DemoLink
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.Glossary
import ua.faric.aolithium.core.Health
import ua.faric.aolithium.core.HistoryMath
import ua.faric.aolithium.core.HistoryPoint
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.Zones
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.HistoryStore
import ua.faric.aolithium.data.LastSnapshot

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
        l.lastSnapshot?.let { snapshot = it; snapshotAt = l.lastSnapshotAt }
        render()
        handler.post(ticker)
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
        if (!remembered && demo == null) {
            remembered = true
            batteries.remember(address, Batteries.scanNames[address] ?: snapshot.model, BmsMatch.Kind.JBD)
        }
        history.record(address, snapshot, atMs)
        lastStore.save(address, snapshot, atMs)
        if (tab == Tab.CHARTS) {
            // графіки не перемальовуємо щосекунди — лише підвантажуємо нові точки раз на 30 с
            if (atMs - pointsLoadedAt > 30_000) loadHistory()
            renderHead()
        } else {
            render()
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
        if (t == Tab.CHARTS) loadHistory()
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
            caption = "${Fmt.num(s.remainAh, 1)} з ${Fmt.num(s.fullAh, 1)} А·год"
            noData = data == Data.NONE
            setOnClickListener { showInfo("zones") }
        }, ui.matchWrap())
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
        val (curText, curColor) = when (s.flow) {
            BatterySnapshot.Flow.CHARGING -> "▲ +" + Fmt.amps(s.current) to Palette.success
            BatterySnapshot.Flow.DISCHARGING -> "▼ −" + Fmt.amps(-s.current) to Palette.danger
            BatterySnapshot.Flow.IDLE -> Fmt.amps(s.current) to Palette.text
        }
        now.addView(fact("Струм", curText, "current", curColor))
        now.addView(fact("Потужність", Fmt.watts(s.power), "power"))
        s.hoursLeft?.let {
            val name = if (s.flow == BatterySnapshot.Flow.CHARGING) "До повної" else "Вистачить на"
            now.addView(fact(name, "≈ " + Fmt.duration(it), "time"))
        }
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
        val right = mutableListOf<View>()
        if (s.hasProblem) right += problemCard(s)
        right += now
        right += state
        columns(left, right)
    }

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
            card.addView(labelRow("Комірки: ${s.cellsMv.size}", LineIcon.CELLS, "cells"))
            card.addView(CellBarsView(host).apply { set(s.cellsMv, s.balancingCells, s.minCell, s.maxCell) }, ui.matchWrap())
            s.deltaMv?.let { d ->
                card.addView(fact("Різниця", "${Fmt.millivolts(d)} — ${s.deltaVerdict!!.title}", "delta", verdictColor(s)))
                val hi = s.maxCell!!
                val lo = s.minCell!!
                card.addView(fact("Найвища", "№${hi + 1} · ${Fmt.cellVolts(s.cellsMv[hi])}", "cells", Palette.accent))
                card.addView(fact("Найнижча", "№${lo + 1} · ${Fmt.cellVolts(s.cellsMv[lo])}", "cells", Palette.warning))
            }
            s.averageCellV?.let { card.addView(fact("Середня", Fmt.num(it, 3) + " В", "avgcell")) }
            card.addView(fact("Балансування", if (s.balancingCells.isEmpty()) "ні" else s.balancingCells.joinToString(", ") { "№$it" }, "balance"))
            card.addView(ui.muted("Стовпчик: від 2,5 В (порожня) до 3,65 В (повна). Жовтий — найнижча, блакитний — найвища, зелений — балансується.", 12f))
            cards += card
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
            sum.addView(ui.muted("Ще немає записів. Історія пишеться раз на 10 с, поки застосунок відкритий і на зв'язку.", 14f))
        } else {
            sum.addView(fact("Заряджено", "${Fmt.num(stats.chargedWh, 0)} Вт·год · ${Fmt.num(stats.chargedAh, 1)} А·год", "energy", Palette.success, known = true))
            sum.addView(fact("Віддано", "${Fmt.num(stats.dischargedWh, 0)} Вт·год · ${Fmt.num(stats.dischargedAh, 1)} А·год", "energy", Palette.danger, known = true))
            if (stats.socMin != null) sum.addView(fact("Заряд", "${stats.socMin}–${stats.socMax} %", "soc", known = true))
            sum.addView(fact("Найбільший струм", "▲ ${Fmt.amps(stats.maxChargeA)} · ▼ ${Fmt.amps(stats.maxDischargeA)}", "current", known = true))
            if (stats.tempMin != null) sum.addView(fact("Температура", "${Fmt.celsius(stats.tempMin)} … ${Fmt.celsius(stats.tempMax!!)}", "temp", known = true))
            stats.deltaMaxMv?.let { sum.addView(fact("Найбільша різниця комірок", Fmt.millivolts(it), "delta", known = true)) }
            val share = (stats.coveredMs * 100 / shown.ms).coerceIn(0, 100)
            sum.addView(ui.muted("Записано ${share} % часу (решта — застосунок був закритий).", 12.5f))
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
        val cellColors = listOf(Palette.accent, Palette.success, Palette.warning, Palette.violet)
        val cellsN = pts.lastOrNull()?.cellsMv?.size ?: 0
        val cChart = ChartView(host).apply {
            set((0 until cellsN).map { i -> ser("№${i + 1}", cellColors[i % cellColors.size]) { p -> p.cellsMv.getOrNull(i)?.div(1000.0) } }, from, to, "В", 3, gapMs = gap)
        }
        val left = listOf(sum, chartCard("Заряд, %", LineIcon.BATTERY, "soc", socChart), chartCard("Струм, А", LineIcon.BOLT, "current", curChart,
            listOf("заряд" to Palette.success, "розряд" to Palette.danger)), chartCard("Потужність, Вт", LineIcon.BOLT, "power", pwrChart))
        val right = listOf(chartCard("Напруга, В", LineIcon.PULSE, "voltage", vChart), chartCard("Температура, °C", LineIcon.THERMO, "temp", tChart,
            listOf("макс." to Palette.warning, "мін." to Palette.accent)), chartCard("Різниця комірок, мВ", LineIcon.CELLS, "delta", dChart),
            chartCard("Комірки, В", LineIcon.CELLS, "cells", cChart, (0 until cellsN).map { "№${it + 1}" to cellColors[it % cellColors.size] }))
        columns(left, right)
        body.addView(ui.muted("Торкнись графіка й веди пальцем — побачиш значення в будь-який момент.", 12.5f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
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
        hist.addView(fact("Як часто", "раз на 10 с, поки відкрито", "history", known = true))
        hist.addView(fact("Скільки зберігається", "${HistoryStore.KEEP_DAYS} днів", "history", known = true))
        if (demo == null && days > 0) hist.addView(ui.button("Стерти історію цієї батареї", Ui.Kind.DANGER) { showClearHistory() }, ui.matchWrap())

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

        columns(listOf(actions, hist, help), listOf(dict))
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
    }
}

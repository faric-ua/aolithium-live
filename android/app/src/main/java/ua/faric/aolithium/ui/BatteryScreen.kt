package ua.faric.aolithium.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import ua.faric.aolithium.ble.BatteryLink
import ua.faric.aolithium.ble.DemoLink
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.data.Batteries

/**
 * Живі показники однієї батареї: заряд, ключі, захисти, кожна комірка, температури, плата.
 * Читання кожні 2 с, поки екран відкритий; сховав застосунок — через 5 с відключаємось
 * (щоб батарея була вільна для Aolithium на інших телефонах).
 * Альбомна (≥ 600 dp) — дві колонки.
 */
class BatteryScreen(host: MainActivity, private val address: String, state: Bundle?) : Screen(host), BatteryLink.Listener {
    private val batteries = Batteries(host)
    private val demo = DemoLink.sceneOf(address)
    private var link: BatteryLink? = null
    private var status = LinkStatus.IDLE
    private var snapshot: BatterySnapshot? = null
    private var snapshotAt = 0L
    private var remembered = false
    private var renameDraft = state?.getString(KEY_DRAFT)

    private val handler = Handler(Looper.getMainLooper())
    private val head = ui.column(6)
    private val leftCol = ui.column(14)
    private val rightCol = ui.column(14)
    private val updated = ui.muted("", 12.5f)
    override val view: View

    private val ticker = object : Runnable {
        override fun run() {
            renderUpdated()
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        val body = ui.column(14)
        body.addView(head, ui.matchWrap())
        if (ui.twoPane) {
            body.addView(ui.row(16).apply {
                gravity = Gravity.TOP
                addView(leftCol, ui.weight(1f))
                addView(rightCol, ui.weight(1f))
            }, ui.matchWrap())
        } else {
            body.addView(leftCol, ui.matchWrap())
            body.addView(rightCol, ui.matchWrap())
        }
        body.addView(ui.muted(host.versionLine, 12f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        view = ui.scroll(body)
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
    }

    override fun restoreDialog(key: String): Boolean {
        when (key) {
            DLG_RENAME -> showRename()
            DLG_LOG -> showLog()
            DLG_FORGET -> showForget()
            else -> return false
        }
        return true
    }

    // ---------- BatteryLink.Listener ----------

    override fun onStatus(status: LinkStatus) {
        this.status = status
        render()
    }

    override fun onSnapshot(snapshot: BatterySnapshot, atMs: Long) {
        this.snapshot = snapshot
        snapshotAt = atMs
        if (!remembered && demo == null) {
            remembered = true
            batteries.remember(address, Batteries.scanNames[address], BmsMatch.Kind.JBD)
        }
        render()
    }

    // ---------- малювання ----------

    private fun render() {
        renderHead()
        leftCol.removeAllViews()
        rightCol.removeAllViews()
        val s = snapshot
        if (s == null) {
            leftCol.addView(waitingCard(), ui.matchWrap())
        } else {
            if (status.phase != LinkStatus.Phase.LIVE) leftCol.addView(statusCard(), ui.matchWrap())
            leftCol.addView(socCard(s), ui.matchWrap())
            leftCol.addView(switchesCard(s), ui.matchWrap())
            if (s.hasProblem) leftCol.addView(problemCard(s), ui.matchWrap())
            rightCol.addView(nowCard(s), ui.matchWrap())
            if (s.cellsMv.isNotEmpty()) rightCol.addView(cellsCard(s), ui.matchWrap())
            rightCol.addView(boardCard(s), ui.matchWrap())
            s.errorCounts?.let { rightCol.addView(countsCard(it), ui.matchWrap()) }
        }
        rightCol.addView(actionsCard(), ui.matchWrap())
    }

    private fun renderHead() {
        head.removeAllViews()
        head.addView(ui.title(title, 22f))
        val row = FlowLayout(host, ui.dp(8), ui.dp(6))
        row.addView(ui.pill(status.phase.title, tone(status.phase)))
        (updated.parent as? ViewGroup)?.removeView(updated)
        row.addView(updated)
        head.addView(row, ui.matchWrap())
        renderUpdated()
    }

    private fun renderUpdated() {
        updated.text = if (snapshotAt == 0L) "" else "оновлено " + Fmt.ago(System.currentTimeMillis() - snapshotAt)
    }

    private fun tone(p: LinkStatus.Phase) = when (p) {
        LinkStatus.Phase.LIVE -> Ui.Tone.GOOD
        LinkStatus.Phase.CONNECTING, LinkStatus.Phase.READING -> Ui.Tone.INFO
        LinkStatus.Phase.LOST, LinkStatus.Phase.NO_ANSWER -> Ui.Tone.WARN
        LinkStatus.Phase.FAILED -> Ui.Tone.BAD
        LinkStatus.Phase.IDLE -> Ui.Tone.NEUTRAL
    }

    private fun waitingCard(): View {
        val failed = status.phase == LinkStatus.Phase.FAILED || status.phase == LinkStatus.Phase.NO_ANSWER
        val card = if (failed) ui.card(fill = Palette.dangerFill, stroke = Palette.danger) else ui.card()
        card.addView(ui.label("Зв'язок", LineIcon.BLUETOOTH))
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

    private fun retryRow(): View = FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
        addView(ui.iconButton(LineIcon.REPEAT, "Підключити ще раз", Ui.Kind.PRIMARY) { reconnect() })
        addView(ui.button("Журнал зв'язку", Ui.Kind.QUIET) { showLog() })
    }

    private fun reconnect() {
        LinkHolder.release(this)
        val l = LinkHolder.restart(host, address)
        link = l
        l.listener = this
        status = l.lastStatus
        render()
    }

    private fun socCard(s: BatterySnapshot): View {
        val card = ui.card(6)
        card.addView(ui.label("Заряд", LineIcon.BATTERY))
        val ring = SocRingView(host).apply {
            percent = s.soc
            caption = "${Fmt.num(s.remainAh, 2)} з ${Fmt.ampHours(s.fullAh)}"
        }
        card.addView(ring, ui.matchWrap())
        val flow = when (s.flow) {
            BatterySnapshot.Flow.CHARGING -> "Заряджається" + (s.hoursLeft?.let { " · до повної ≈ ${Fmt.duration(it)}" } ?: "")
            BatterySnapshot.Flow.DISCHARGING -> "Віддає енергію" + (s.hoursLeft?.let { " · вистачить ≈ на ${Fmt.duration(it)}" } ?: "")
            BatterySnapshot.Flow.IDLE -> "Стоїть: струму майже нема"
        }
        card.addView(ui.text(flow, 15f, if (s.flow == BatterySnapshot.Flow.IDLE) Palette.muted else Palette.text, medium = true).apply {
            gravity = Gravity.CENTER
        }, ui.matchWrap())
        if (s.flow != BatterySnapshot.Flow.IDLE) {
            card.addView(ui.muted("Орієнтовно, за нинішнім струмом.", 12f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        }
        return card
    }

    private fun switchesCard(s: BatterySnapshot): View {
        val card = ui.card()
        card.addView(ui.label("Стан плати", LineIcon.SHIELD))
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        row.addView(ui.pill(if (s.chargeOn) "Заряд: дозволено" else "Заряд: вимкнено", if (s.chargeOn) Ui.Tone.GOOD else Ui.Tone.BAD))
        row.addView(ui.pill(if (s.dischargeOn) "Розряд: дозволено" else "Розряд: вимкнено", if (s.dischargeOn) Ui.Tone.GOOD else Ui.Tone.BAD))
        row.addView(
            if (s.balancingCells.isEmpty()) ui.pill("Балансування: ні", Ui.Tone.NEUTRAL)
            else ui.pill("Балансування: " + s.balancingCells.joinToString(", ") { "№$it" }, Ui.Tone.INFO),
        )
        row.addView(ui.pill(if (s.hasProblem) "Захист спрацював" else "Захист: усе гаразд", if (s.hasProblem) Ui.Tone.BAD else Ui.Tone.GOOD))
        card.addView(row, ui.matchWrap())
        card.addView(ui.muted("В Aolithium це «Charge / Discharge Switch: open» (дозволено) і «Protection State: close» (усе гаразд).", 12.5f))
        return card
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

    private fun nowCard(s: BatterySnapshot): View {
        val card = ui.card(8)
        card.addView(ui.label("Зараз", LineIcon.PULSE))
        card.addView(ui.fact("Напруга", Fmt.volts(s.voltage)))
        card.addView(ui.fact("Струм", Fmt.amps(s.current)))
        card.addView(ui.fact("Потужність", Fmt.watts(s.power)))
        if (s.temperatures.isEmpty()) {
            card.addView(ui.fact("Температура", "—"))
        } else {
            s.temperatures.forEachIndexed { i, t ->
                val name = if (s.temperatures.size == 1) "Температура" else "Температура ${i + 1}"
                val color = if (t < 0 || t > 50) Palette.warning else Palette.text
                card.addView(ui.fact(name, Fmt.celsius(t), color))
            }
        }
        card.addView(ui.fact("Цикли", s.cycles.toString()))
        s.averageCellV?.let { card.addView(ui.fact("Середня комірка", Fmt.num(it, 3) + " В")) }
        card.addView(ui.fact("Ємність (повна)", Fmt.ampHours(s.fullAh)))
        card.addView(ui.fact("Залишок", Fmt.ampHours(s.remainAh)))
        return card
    }

    private fun cellsCard(s: BatterySnapshot): View {
        val card = ui.card(8)
        card.addView(ui.label("Комірки: ${s.cellsMv.size}", LineIcon.CELLS))
        card.addView(CellBarsView(host).apply { set(s.cellsMv, s.balancingCells, s.minCell, s.maxCell) }, ui.matchWrap())
        val delta = s.deltaMv
        if (delta != null) {
            val verdict = s.deltaVerdict!!
            val color = when (verdict) {
                BatterySnapshot.Verdict.GOOD -> Palette.success
                BatterySnapshot.Verdict.WATCH -> Palette.warning
                BatterySnapshot.Verdict.BAD -> Palette.danger
            }
            card.addView(ui.fact("Різниця", "${Fmt.millivolts(delta)} — ${verdict.title}", color))
            val hi = s.maxCell!!
            val lo = s.minCell!!
            card.addView(ui.muted("Найвища — №${hi + 1} (${Fmt.cellVolts(s.cellsMv[hi])}), найнижча — №${lo + 1} (${Fmt.cellVolts(s.cellsMv[lo])}).", 13f))
        }
        card.addView(ui.muted("Стовпчик: від 2,5 В (порожня) до 3,65 В (повна). Жовтий — найнижча, блакитний — найвища, зелений — балансується.", 12f))
        return card
    }

    private fun boardCard(s: BatterySnapshot): View {
        val card = ui.card(8)
        card.addView(ui.label("Плата", LineIcon.CHIP))
        card.addView(ui.fact("Тип", "JBD (Jiabaida)"))
        s.model?.let { card.addView(ui.fact("Назва плати", it)) }
        s.firmware?.let { card.addView(ui.fact("Версія програми", it)) }
        s.made?.let { card.addView(ui.fact("Дата виготовлення", it.toString())) }
        card.addView(ui.fact("Комірок", s.cellCount.toString()))
        card.addView(ui.fact("Датчиків температури", s.temperatures.size.toString()))
        if (demo == null) card.addView(ui.fact("Адреса", BmsMatch.shortAddress(address)))
        return card
    }

    private fun countsCard(counts: List<Pair<String, Int>>): View {
        val card = ui.card(8)
        card.addView(ui.label("Захисти за весь час", LineIcon.LIST))
        card.addView(ui.muted("Скільки разів плата вмикала кожен захист (з моменту виготовлення чи скидання).", 12.5f))
        for ((name, n) in counts) card.addView(ui.fact(name, n.toString(), if (n > 0) Palette.warning else Palette.muted))
        return card
    }

    private fun actionsCard(): View {
        val card = ui.card()
        card.addView(ui.label("Дії", LineIcon.SLIDERS))
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        if (demo == null) row.addView(ui.iconButton(LineIcon.PEN, "Перейменувати", Ui.Kind.NORMAL) { showRename() })
        row.addView(ui.button("Журнал зв'язку", Ui.Kind.NORMAL) { showLog() })
        if (demo == null && batteries.find(address) != null) row.addView(ui.button("Прибрати зі списку", Ui.Kind.QUIET) { showForget() })
        card.addView(row, ui.matchWrap())
        card.addView(ui.muted("Застосунок лише читає плату — нічого в ній не змінює. Поки він підключений, Aolithium на іншому телефоні не підключиться: сховай цей застосунок — через 5 с зв'язок звільниться.", 12.5f))
        return card
    }

    // ---------- вікна ----------

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
        const val KEY_DRAFT = "renameDraft"
        const val DLG_RENAME = "rename"
        const val DLG_LOG = "log"
        const val DLG_FORGET = "forget"
        const val LOG_SHOW_CHARS = 6_000
    }
}

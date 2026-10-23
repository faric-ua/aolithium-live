package ua.faric.aolithium.core

import java.util.TimeZone
import kotlin.math.abs

/**
 * Сповіщення (v0.3, TODO T4). faric 19.10 19:44: «всі типи сповіщень… якщо можеш про щось ще — сповіщай».
 *
 * Тут лише правила без Android (тести `AlertsTest`): на кожен знімок батареї — новий [State] і список подій.
 * Головне правило — **не спамити**: кожне сповіщення приходить один раз, доки стан не мине
 * (і має «запас», щоб не стрибати туди-сюди на межі: 20 % → знову лише після 25 %, холод → лише після +2 °C).
 * Показ у шторці — `bg/Notifier.kt`; знімки приходять і з фонового запису (раз на ~5 хв), і з відкритого екрана.
 *
 * Обмеження (faric 19.10 19:44): Bluetooth дістає лише поруч із батареєю. Телефон далеко — даних нема, тож
 * і сповіщень про світло чи заряд нема; є лише тихе «Нема даних» через годину.
 */
object Alerts {
    enum class Level { HIGH, DEFAULT, QUIET }

    /** Види сповіщень — кожен свій перемикач у «Ще» і свій канал Android (звук можна змінити в налаштуваннях телефона). */
    enum class Kind(val key: String, val level: Level) {
        OUTAGE("outage", Level.HIGH),
        LOW("low", Level.HIGH),
        FULL("full", Level.DEFAULT),
        PROTECTION("protect", Level.HIGH),
        CELLS("cells", Level.DEFAULT),
        TEMP("temp", Level.HIGH),
        NO_DATA("nodata", Level.QUIET),
        /** T39 (v0.4.9): смужка «Заряджається» — правила в `ChargeNote`, не в `onSnapshot`. Останнім: номери в шторці не зсуваються. */
        CHARGING("charging", Level.QUIET),
        ;

        /** Назва (перемикач у «Ще», канал Android) — обома мовами (T12), через `get()`: мову можуть перемкнути. */
        val title: String get() = when (this) {
            OUTAGE -> tr("Світло зникло / повернулось", "Power outage / restored")
            LOW -> tr("Мало заряду", "Low charge")
            FULL -> tr("Батарея повна", "Battery full")
            PROTECTION -> tr("Захист плати", "BMS protection")
            CELLS -> tr("Велика різниця комірок", "Large cell difference")
            TEMP -> tr("Холод / спека", "Cold / heat")
            NO_DATA -> tr("Нема даних понад 1 год", "No data for over 1 h")
            CHARGING -> tr("Заряджається (смужка)", "Charging (progress bar)")
        }

        /** Підказка під назвою; "" — без підказки. */
        val hint: String get() = when (this) {
            OUTAGE, CELLS -> ""
            LOW -> "20 %, 10 %, 5 %"
            FULL -> tr("щоб вчасно вимкнути генератор", "to turn off the generator in time")
            PROTECTION -> tr("і коли плата вимкнула заряд чи розряд", "also when the BMS turns off charge or discharge")
            TEMP -> tr("нижче 0 °C, вище 50 °C", "below 0 °C, above 50 °C")
            NO_DATA -> tr("тихе, без звуку", "quiet, no sound")
            CHARGING -> tr("тихе: %, до повної, зникає саме", "quiet: %, time to full, goes away by itself")
        }

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    /** Подія для шторки. [cancel] — прибрати сповіщення цього виду (стан минув, нічого нового казати). */
    /** [urgent] — T50: серйозне (перегрів, струм, КЗ): у шторці кнопки «Подзвонити», у боті — номери (`Emergency`). */
    data class Event(val kind: Kind, val title: String, val text: String, val cancel: Boolean = false, val urgent: Boolean = false)

    // ---------- пороги ----------

    /** Пороги «мало заряду» — ті самі, що в «Вистачить на». */
    val LOW_LEVELS = TimeLeft.THRESHOLDS
    /** «Мало заряду» знову спрацює для порогу, лише коли заряд піднявся на стільки вище нього. */
    const val LOW_RESET = 5
    /** «Повна» знову спрацює, лише коли заряд опустився до цього. */
    const val FULL_RESET = 95
    /** Від цього заряду «перезаряд комірки / батареї» — це звичайний кінець заряду, а не тривога (лічильник faric — 2016). */
    const val TOP_SOC = 95
    /** Різниця комірок: у середині заряду (15…90 %) уже 30 мВ — незвично; будь-де нижче 95 % — 100 мВ. */
    const val CELLS_MID_MV = BatterySnapshot.Verdict.WATCH_FROM_MV
    const val CELLS_ANY_MV = BatterySnapshot.Verdict.BAD_FROM_MV
    val CELLS_MID_SOC = 15..90
    /** Різниця знову спрацює, лише коли спадала нижче цього. */
    const val CELLS_RESET_MV = 20
    const val COLD_C = 0.0
    const val COLD_RESET_C = 2.0
    const val HOT_C = 50.0
    const val HOT_RESET_C = 45.0
    /** T78: довший пропуск у даних під час відключення — не знаємо, що було, старе відключення забуваємо. */
    const val OUTAGE_FORGET_MS = 2 * 3_600_000L
    /** Стільки без жодного знімка — «Нема даних». */
    const val NO_DATA_MS = 3_600_000L

    // ---------- стан ----------

    /** Що вже сказали — щоб не повторювати. Зберігається в пам'яті телефона ([encode] / [decode]). */
    data class State(
        /** Був хоч один знімок (перший — лише запам'ятати, «повна» на першому знімку не кажемо). */
        val seen: Boolean = false,
        val lastT: Long = 0L,
        /** Світла нема (батарея віддає вже кілька хвилин). */
        val outage: Boolean = false,
        val outStart: Long = 0L,
        val outSoc: Int = 0,
        /** Під час відключення був довгий пропуск у даних (телефон далеко) — тривалість лише приблизна. */
        val outGap: Boolean = false,
        /** Коли вперше побачили протилежне (почала / перестала віддавати), 0 — ні; підтверджуємо через [Outages.MIN_MS]. */
        val cand: Long = 0L,
        val candSoc: Int = 0,
        /**
         * T78: розряд помітили одразу після довгого пропуску (телефон був далеко) — коли саме зникло світло, не знаємо.
         * [candLost] — останній знімок перед пропуском (для кандидата), [outLostAt] — те саме для підтвердженого відключення.
         */
        val candLost: Long = 0L,
        val outLate: Boolean = false,
        val outLostAt: Long = 0L,
        /** Найнижчий поріг «мало заряду», про який уже сказали; 0 — ні про який. */
        val low: Int = 0,
        val full: Boolean = false,
        /** Які захисти / вимкнені ключі вже показали. */
        val prot: Set<String> = emptySet(),
        val cells: Boolean = false,
        val cold: Boolean = false,
        val hot: Boolean = false,
        val noData: Boolean = false,
    ) {
        fun encode(): String = listOf(
            "seen" to (if (seen) "1" else "0"), "t" to "$lastT", "out" to (if (outage) "1" else "0"), "os" to "$outStart",
            "osoc" to "$outSoc", "og" to (if (outGap) "1" else "0"), "c" to "$cand", "csoc" to "$candSoc", "low" to "$low",
            "full" to (if (full) "1" else "0"), "prot" to prot.sorted().joinToString(","), "cells" to (if (cells) "1" else "0"),
            "cold" to (if (cold) "1" else "0"), "hot" to (if (hot) "1" else "0"), "nd" to (if (noData) "1" else "0"),
            "cl" to "$candLost", "ol" to (if (outLate) "1" else "0"), "olt" to "$outLostAt",
        ).joinToString(";") { "${it.first}=${it.second}" }

        companion object {
            /** Зіпсований чи старий запис — порожній стан (гірше лише те, що одне сповіщення може повторитись). */
            fun decode(raw: String?): State {
                if (raw.isNullOrBlank()) return State()
                val m = raw.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                fun b(k: String) = m[k] == "1"
                fun l(k: String) = m[k]?.toLongOrNull() ?: 0L
                fun i(k: String) = m[k]?.toIntOrNull() ?: 0
                return State(
                    seen = b("seen"), lastT = l("t"), outage = b("out"), outStart = l("os"), outSoc = i("osoc"), outGap = b("og"),
                    cand = l("c"), candSoc = i("csoc"), low = i("low"), full = b("full"),
                    prot = m["prot"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                    cells = b("cells"), cold = b("cold"), hot = b("hot"), noData = b("nd"),
                    candLost = l("cl"), outLate = b("ol"), outLostAt = l("olt"),
                )
            }
        }
    }

    data class Step(val state: State, val events: List<Event>)

    // ---------- новий знімок ----------

    fun onSnapshot(st: State, s: BatterySnapshot, t: Long, tz: TimeZone = TimeZone.getDefault()): Step {
        val ev = ArrayList<Event>()
        var n = st
        if (n.noData) {
            ev += Event(Kind.NO_DATA, "", "", cancel = true)
            n = n.copy(noData = false)
        }
        n = outage(n, s, t, tz, ev)
        n = low(n, s, ev)
        n = full(n, s, ev)
        n = protection(n, s, ev)
        n = cells(n, s, ev)
        n = temperature(n, s, ev)
        return Step(n.copy(seen = true, lastT = t), ev)
    }

    /** Фоновий запис не дістав батарею. [lastOkAt] — час останнього знімка (з будь-якого місця), null — ще не було. */
    fun onNoData(st: State, lastOkAt: Long?, now: Long, tz: TimeZone = TimeZone.getDefault()): Step {
        if (lastOkAt == null || st.noData || now - lastOkAt < NO_DATA_MS) return Step(st, emptyList())
        val e = Event(
            Kind.NO_DATA,
            tr("Нема даних з ${Background.hhmm(lastOkAt, tz)}", "No data since ${Background.hhmm(lastOkAt, tz)}"),
            tr(
                "Телефон далеко від батареї або вона зайнята іншим телефоном. Про світло й заряд дізнаюсь, коли знову буду поруч.",
                "The phone is far from the battery, or another phone is using it. I will know about power and charge when I am near again.",
            ),
        )
        return Step(st.copy(noData = true), listOf(e))
    }

    // ---------- світло ----------

    /**
     * T78 (скріни faric 23.10 18:55, мамин сторож): «Відключення почалось о 17:03» прийшло о 15:11 — стан «світла нема»
     * лишився з попереднього дня, поки телефон був далеко, а дати в тексті не було. Тепер: (1) довший за [OUTAGE_FORGET_MS]
     * пропуск під час відключення — не знаємо, що було: старе відключення забуваємо й дивимось заново, як `Outages.find`;
     * (2) розряд, помічений одразу після пропуску, — «помітив о …, до того телефон не бачив батарею з …»;
     * (3) час не сьогодні — з датою ([at]); (4) у тексті «повернулось» — коли саме повернулось і з якого часу не було.
     */
    private fun outage(st: State, s: BatterySnapshot, t: Long, tz: TimeZone, ev: MutableList<Event>): State {
        var n = st
        val gapMs = if (st.seen) t - st.lastT else 0L
        val gap = st.seen && gapMs > Outages.MAX_GAP_MS
        if (gap) n = n.copy(cand = 0L, candLost = 0L, outGap = n.outGap || n.outage)
        if (n.outage && gap && gapMs > OUTAGE_FORGET_MS) {
            n = n.copy(outage = false, outGap = false, outLate = false, outLostAt = 0L)
        }
        if (!n.outage) {
            if (s.current > -Outages.START_A) return n.copy(cand = 0L, candLost = 0L)
            if (n.cand == 0L) return n.copy(cand = t, candSoc = s.soc, candLost = if (gap) st.lastT else 0L)
            if (t - n.cand < Outages.MIN_MS) return n
            val late = n.candLost > 0L
            val note = if (late) tr(
                " · помітив о ${at(n.cand, t, tz)}; до того телефон не бачив батарею з ${at(n.candLost, t, tz)}",
                " · noticed at ${at(n.cand, t, tz)}; before that the phone had not seen the battery since ${at(n.candLost, t, tz)}",
            ) else ""
            ev += Event(Kind.OUTAGE, tr("Світло зникло", "Power outage"), outageStartText(s) + note)
            return n.copy(outage = true, outStart = n.cand, outSoc = n.candSoc, outGap = false, outLate = late,
                outLostAt = n.candLost, cand = 0L, candLost = 0L)
        }
        if (s.current <= Outages.END_A) return n.copy(cand = 0L)
        if (n.cand == 0L) return n.copy(cand = t, candSoc = s.soc)
        if (t - n.cand < Outages.MIN_MS) return n
        val back = at(n.cand, t, tz)
        val start = at(n.outStart, t, tz)
        val long = Fmt.duration((n.cand - n.outStart) / 3_600_000.0)
        val soc = tr("заряд ${n.outSoc} → ${n.candSoc} %", "charge ${n.outSoc} → ${n.candSoc} %")
        val now = if (s.flow == BatterySnapshot.Flow.CHARGING) tr("батарея заряджається", "battery is charging")
            else tr("батарея стоїть", "battery is idle")
        val text = when {
            n.outLate -> tr(
                "О $back · не було щонайменше $long (помітив о $start; раніше телефон був далеко) · $soc · $now",
                "At $back · gone for at least $long (noticed at $start; the phone was far before that) · $soc · $now",
            )
            n.outGap -> tr(
                "О $back · зникло о $start; поки телефон був далеко, даних нема — тривалість приблизно $long · $soc · $now",
                "At $back · went out at $start; no data while the phone was far — about $long · $soc · $now",
            )
            else -> tr("О $back · не було $long (з $start) · $soc · $now", "At $back · gone for $long (since $start) · $soc · $now")
        }
        ev += Event(Kind.OUTAGE, tr("Світло повернулось", "Power restored"), text)
        return n.copy(outage = false, outGap = false, outLate = false, outLostAt = 0L, cand = 0L)
    }

    /** T78: час події — «13:00», якщо той самий день, що й [now], інакше з датою «22.10, 17:03». */
    fun at(ms: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).apply { timeZone = tz }
        val hm = Background.hhmm(ms, tz)
        if (day.format(java.util.Date(ms)) == day.format(java.util.Date(now))) return hm
        return java.text.SimpleDateFormat("dd.MM", java.util.Locale.ROOT).apply { timeZone = tz }.format(java.util.Date(ms)) + ", " + hm
    }

    /**
     * T78: стан «світла нема» людською мовою — для файлу «Дані для перевірки» (/diag), щоб на відстані було видно,
     * що «думає» сторож. Українською навмисно: файл читає Claude.
     */
    fun describe(st: State, now: Long, tz: TimeZone = TimeZone.getDefault()): String = buildString { // uk-only: файл для Claude
        if (!st.seen) return "ще не бачив жодного знімка" // uk-only: файл для Claude
        append("останній знімок ${at(st.lastT, now, tz)} · ") // uk-only: файл для Claude
        if (st.outage) {
            append("світла нема з ${at(st.outStart, now, tz)} (заряд тоді ${st.outSoc} %)") // uk-only: файл для Claude
            if (st.outGap) append(" · був пропуск у даних") // uk-only: файл для Claude
            if (st.outLate) append(" · помічено після пропуску з ${at(st.outLostAt, now, tz)}") // uk-only: файл для Claude
            if (st.cand > 0) append(" · з ${at(st.cand, now, tz)} схоже, що світло повернулось (чекаю 3 хв)") // uk-only: файл для Claude
        } else {
            append("світло є") // uk-only: файл для Claude
            if (st.cand > 0) append(" · з ${at(st.cand, now, tz)} батарея віддає (чекаю 3 хв)") // uk-only: файл для Claude
        }
    }

    fun outageStartText(s: BatterySnapshot): String {
        val w = Fmt.watts(abs(s.power))
        val parts = mutableListOf(tr("Батарея віддає $w", "Battery discharging at $w"), "${s.soc} %")
        TimeLeft.discharge(s.remainAh, s.fullAh, s.current)?.let { d ->
            val to20 = d.toThreshold.firstOrNull { it.first == 20 }?.second
            parts += if (to20 != null) {
                tr("вистачить ≈ ${Fmt.duration(to20)} до 20 %", "lasts ≈ ${Fmt.duration(to20)} to 20 %")
            } else {
                tr("до кінця ≈ ${Fmt.duration(d.totalH)}", "until empty ≈ ${Fmt.duration(d.totalH)}")
            }
        }
        return parts.joinToString(" · ")
    }

    // ---------- мало заряду ----------

    private fun low(st: State, s: BatterySnapshot, ev: MutableList<Event>): State {
        var level = st.low
        // Заряд піднявся — «відпустити» пороги, вище яких уже на LOW_RESET (10 → 20 → нічого).
        while (level != 0 && s.soc >= level + LOW_RESET) level = LOW_LEVELS.filter { it > level }.minOrNull() ?: 0
        val crossed = LOW_LEVELS.filter { s.soc <= it }.minOrNull()
        if (crossed != null && (level == 0 || crossed < level) && s.flow != BatterySnapshot.Flow.CHARGING) {
            ev += Event(Kind.LOW, tr("Мало заряду: ${s.soc} %", "Low charge: ${s.soc} %"), lowText(s, crossed))
            level = crossed
        }
        return st.copy(low = level)
    }

    fun lowText(s: BatterySnapshot, crossed: Int): String {
        val next = LOW_LEVELS.filter { it < crossed }.maxOrNull()
        val d = TimeLeft.discharge(s.remainAh, s.fullAh, s.current)
        val w = Fmt.watts(abs(s.power))
        return when {
            crossed == LOW_LEVELS.min() -> tr(
                "Нижче ${crossed} % не варто — потрібна зарядка (генератор?).",
                "Better not go below ${crossed} % — it needs charging (generator?).",
            ) + (d?.let {
                val left = Fmt.duration(it.totalH)
                tr(" Віддає $w, до кінця ≈ $left.", " Discharging at $w, until empty ≈ $left.")
            } ?: "")
            d != null && next != null -> {
                val h = d.toThreshold.firstOrNull { it.first == next }?.second
                val later = LOW_LEVELS.filter { it < crossed }.joinToString(tr(" і ", " and ")) { "$it %" }
                tr("Віддає $w", "Discharging at $w") +
                    (h?.let { tr(" · до $next % ≈ ${Fmt.duration(it)}", " · to $next % ≈ ${Fmt.duration(it)}") } ?: "") +
                    tr(" · далі — $later", " · next — $later")
            }
            else -> tr("Батарея стоїть. Варто зарядити, поки є світло.", "The battery is idle. Better charge it while there is power.")
        }
    }

    // ---------- повна ----------

    private fun topProtection(s: BatterySnapshot): Boolean =
        s.soc >= TOP_SOC && s.protections.any { it == Protection.CELL_OVER || it == Protection.PACK_OVER }

    private fun full(st: State, s: BatterySnapshot, ev: MutableList<Event>): State {
        val isFull = s.soc >= 100 || topProtection(s) || (s.soc >= TOP_SOC && !s.chargeOn)
        if (st.full) return if (s.soc <= FULL_RESET) st.copy(full = false) else st
        if (!isFull) return st
        if (st.seen) {
            val stopped = topProtection(s) || !s.chargeOn || s.flow != BatterySnapshot.Flow.CHARGING
            ev += Event(
                Kind.FULL,
                tr("Батарея повна — ${s.soc} %", "Battery full — ${s.soc} %"),
                (if (stopped) tr("Плата сама зупинила заряд.", "The BMS stopped charging by itself.")
                else tr("Заряд закінчується, плата зупинить сама.", "Charging is ending, the BMS will stop it by itself.")) +
                    tr(" Якщо працює генератор — можна вимикати.", " If a generator is running, you can turn it off."),
            )
        }
        return st.copy(full = true)
    }

    // ---------- захист ----------

    const val DSG_OFF = "dsg_off"
    const val CHG_OFF = "chg_off"

    /** Які захисти зараз варто показати (кінець заряду — це «повна», не тривога). */
    fun activeProtections(s: BatterySnapshot): Set<String> {
        val top = s.soc >= TOP_SOC
        val keys = LinkedHashSet<String>()
        if (!s.dischargeOn) keys += DSG_OFF
        if (!s.chargeOn && !top) keys += CHG_OFF
        s.protections.filterNot { top && (it == Protection.CELL_OVER || it == Protection.PACK_OVER) }.forEach { keys += it.name }
        s.unknownProtectionBits.forEach { keys += "bit$it" }
        return keys
    }

    private fun protection(st: State, s: BatterySnapshot, ev: MutableList<Event>): State {
        val active = activeProtections(s)
        val fresh = active - st.prot
        if (fresh.isNotEmpty()) {
            ev += protectionEvent(s, active)
        } else if (active.isEmpty() && st.prot.isNotEmpty()) {
            ev += Event(Kind.PROTECTION, tr("Захист знято", "Protection cleared"),
                tr("Плата знову дозволяє заряд і розряд.", "The BMS allows charge and discharge again."))
        }
        return st.copy(prot = active)
    }

    fun protectionEvent(s: BatterySnapshot, active: Set<String>): Event {
        val prots = s.protections.filter { it.name in active }
        val reasons = prots.map { it.title } +
            s.unknownProtectionBits.filter { "bit$it" in active }.map { tr("невідомий захист (біт $it)", "unknown protection (bit $it)") }
        val title = when {
            DSG_OFF in active -> tr("Плата вимкнула розряд", "BMS turned off discharge")
            CHG_OFF in active -> tr("Плата вимкнула заряд", "BMS turned off charging")
            prots.size == 1 -> tr("Захист: ", "Protection: ") + prots[0].title.replaceFirstChar { it.lowercase() }
            else -> tr("Спрацював захист плати", "BMS protection is on")
        }
        val text = buildString {
            if (DSG_OFF in active) append(tr("Батарея зараз не віддає енергію. ", "The battery gives no power now. "))
            when {
                prots.size == 1 && reasons.size == 1 -> append(prots[0].explain)
                reasons.isNotEmpty() -> append(tr("Причина: ", "Reason: ")).append(reasons.joinToString(", ")).append('.')
                else -> append(tr("Плата не пояснила причину — подивись вкладку «Плата».", "The BMS gave no reason — see the \"BMS\" tab."))
            }
        }.trim()
        return Event(Kind.PROTECTION, title, text, urgent = prots.any { it in Emergency.URGENT_PROTECTIONS })
    }

    // ---------- комірки ----------

    private fun cells(st: State, s: BatterySnapshot, ev: MutableList<Event>): State {
        val d = s.deltaMv ?: return st
        if (st.cells) return if (d < CELLS_RESET_MV) st.copy(cells = false) else st
        val alarm = (d >= CELLS_ANY_MV && s.soc < TOP_SOC) || (d >= CELLS_MID_MV && s.soc in CELLS_MID_SOC)
        if (!alarm) return st
        ev += Event(Kind.CELLS, tr("Велика різниця комірок: ", "Large cell difference: ") + Fmt.millivolts(d), cellsText(s))
        return st.copy(cells = true)
    }

    fun cellsText(s: BatterySnapshot): String {
        val avg = s.cellsMv.average()
        val lo = s.minCell ?: 0
        val hi = s.maxCell ?: 0
        val who = if (avg - s.cellsMv[lo] >= s.cellsMv[hi] - avg) tr("№${lo + 1} нижча за інші", "No. ${lo + 1} is lower than others")
            else tr("№${hi + 1} вища за інші", "No. ${hi + 1} is higher than others")
        return tr("$who при ${s.soc} %. Подивись «Комірки».", "$who at ${s.soc} %. See \"Cells\".")
    }

    // ---------- температура ----------

    private fun temperature(st: State, s: BatterySnapshot, ev: MutableList<Event>): State {
        if (s.temperatures.isEmpty()) return st
        val min = s.temperatures.min()
        val max = s.temperatures.max()
        var n = st
        if (n.cold) {
            if (min >= COLD_RESET_C) n = n.copy(cold = false)
        } else if (min < COLD_C) {
            ev += Event(Kind.TEMP, tr("Холодно: ", "Cold: ") + Fmt.celsius(min),
                tr("Нижче 0 °C плата не дає заряджати. Віддавати може.", "Below 0 °C the BMS blocks charging. Discharge still works."))
            n = n.copy(cold = true)
        }
        if (n.hot) {
            if (max <= HOT_RESET_C) n = n.copy(hot = false)
        } else if (max > HOT_C) {
            ev += Event(Kind.TEMP, tr("Гаряче: ", "Hot: ") + Fmt.celsius(max), tr(
                "Вище 50 °C плата може вимкнути заряд і розряд. Перевір, чи нічого не гріє батарею.",
                "Above 50 °C the BMS may turn off charge and discharge. Check that nothing heats the battery.",
            ), urgent = true)
            n = n.copy(hot = true)
        }
        return n
    }
}

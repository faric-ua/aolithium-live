package ua.faric.aolithium.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * T48 «Сторож» (faric 20.10 23:04 і 21.10 01:53): телефон біля батареї шле в Telegram людям, які під'єднались до бота.
 * Тут — **що** і **коли** слати, без Android і без мережі (щоб перевірити тестами).
 *
 * Правила світла, заряду, захисту, комірок і температури — ті самі, що й сповіщення в шторці (`Alerts`), але зі своїм
 * станом: вимкнене сповіщення на телефоні-сторожі не глушить Telegram. Своє тут: «кожні −10 %», «розрядилась»,
 * «різкий стрибок», «сторож не бачить батарею» і щоденне «я працюю» — точка контролю: не прийшло — щось сталося
 * зі сторожем (вимкнувся, нема інтернету).
 */
object Guard {
    /** Види — кожен учасник у чаті з ботом вмикає й вимикає їх сам. За замовчуванням — усі. Порядок = порядок кнопок. */
    enum class Kind(val key: String, val icon: String) {
        PROTECTION("prot", "⚠️"),
        TEMP("temp", "🌡️"),
        CELLS("cells", "⚖️"),
        JUMP("jump", "⚡"),
        OUTAGE("outage", "🔌"),
        FULL("full", "🔋"),
        LOW("low", "🪫"),
        STEP("step", "📉"),
        NO_DATA("nodata", "📡"),
        DAILY("daily", "🟢"),
        ;

        val title: String get() = when (this) {
            PROTECTION -> tr("Захист плати", "BMS protection")
            TEMP -> tr("Холод / спека", "Cold / heat")
            CELLS -> tr("Різниця комірок", "Cell difference")
            JUMP -> tr("Різкий стрибок", "Sudden jump")
            OUTAGE -> tr("Світло зникло / повернулось", "Power outage / restored")
            FULL -> tr("Батарея повна", "Battery full")
            LOW -> tr("Мало заряду, розрядилась", "Low charge, empty")
            STEP -> tr("Кожні −10 % заряду", "Every −10 % of charge")
            NO_DATA -> tr("Сторож не бачить батарею", "Guard cannot see the battery")
            DAILY -> tr("Щодня «я працюю»", "Daily \"I am working\"")
        }

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
            val ALL: Set<Kind> = entries.toSet()
        }
    }

    /** Одне повідомлення в Telegram. [battery] — назва батареї (порожньо — про сторожа загалом). */
    data class Msg(val kind: Kind, val title: String, val text: String, val battery: String = "") {
        fun render(): String = buildString {
            append(kind.icon).append(' ').append(title)
            if (battery.isNotEmpty()) append("\n🔋 ").append(battery)
            if (text.isNotEmpty()) append('\n').append(text)
        }
    }

    // ---------- пороги ----------

    /** «Кожні −10 %»: знову про той самий десяток — лише коли заряд піднявся на стільки вище нього (шум ±1 %). */
    const val STEP_RESET = 12
    /** «Розрядилась»: заряд такий або нижче, і батарея не заряджається. Знову — після того, як піднявся до [EMPTY_RESET]. */
    const val EMPTY_SOC = 1
    const val EMPTY_RESET = 10
    /**
     * Стрибок: порівнюємо з «опорою» — найнижчим значенням за останні [JUMP_WINDOW_MS]. Опора нова, коли застаріла
     * або коли значення стало ще нижчим. Повільне потепління (+1,5 °C за 15 хв) — не стрибок.
     */
    const val JUMP_WINDOW_MS = 15 * 60_000L
    const val JUMP_TEMP_C = 5.0
    const val JUMP_DELTA_MV = 50
    /** Після стрибка — тиша стільки, щоб не сипати повідомленнями, поки росте. */
    const val JUMP_QUIET_MS = 30 * 60_000L
    /** Сторож не бачить батарею стільки — кажемо. */
    const val NO_DATA_MS = 30 * 60_000L
    const val DAILY_HOUR_DEFAULT = 9

    // ---------- стан однієї батареї ----------

    data class State(
        val alerts: Alerts.State = Alerts.State(),
        /** Десяток, про який сказали останнім («70» — сказали «70 %»); −1 — ще не знаємо. */
        val step: Int = -1,
        val empty: Boolean = false,
        /** Опори для «стрибка»: найвища температура й різниця комірок, і коли їх узято. */
        val tempAt: Long = 0L,
        val baseTemp: Double = Double.NaN,
        val deltaAt: Long = 0L,
        val baseDelta: Int = -1,
        val jumpAt: Long = 0L,
        val noData: Boolean = false,
    ) {
        fun encode(): String = listOf(
            "a" to alerts.encode().replace(";", "|"), "step" to "$step", "empty" to (if (empty) "1" else "0"),
            "tt" to "$tempAt", "btemp" to (if (baseTemp.isNaN()) "" else "$baseTemp"), "dt" to "$deltaAt", "bd" to "$baseDelta",
            "ja" to "$jumpAt", "nd" to (if (noData) "1" else "0"),
        ).joinToString(";") { "${it.first}=${it.second}" }

        companion object {
            fun decode(raw: String?): State {
                if (raw.isNullOrBlank()) return State()
                val m = raw.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                return State(
                    alerts = Alerts.State.decode(m["a"]?.replace("|", ";")),
                    step = m["step"]?.toIntOrNull() ?: -1,
                    empty = m["empty"] == "1",
                    tempAt = m["tt"]?.toLongOrNull() ?: 0L,
                    baseTemp = m["btemp"]?.toDoubleOrNull() ?: Double.NaN,
                    deltaAt = m["dt"]?.toLongOrNull() ?: 0L,
                    baseDelta = m["bd"]?.toIntOrNull() ?: -1,
                    jumpAt = m["ja"]?.toLongOrNull() ?: 0L,
                    noData = m["nd"] == "1",
                )
            }
        }
    }

    data class Step(val state: State, val messages: List<Msg>)

    // ---------- новий знімок ----------

    fun onSnapshot(st: State, s: BatterySnapshot, t: Long, battery: String, tz: TimeZone = TimeZone.getDefault()): Step {
        val out = ArrayList<Msg>()
        var n = st
        if (n.noData) {
            out += Msg(Kind.NO_DATA, tr("Знову бачу батарею", "I can see the battery again"), short(s), battery)
            n = n.copy(noData = false)
        }
        val a = Alerts.onSnapshot(n.alerts, s, t, tz)
        n = n.copy(alerts = a.state)
        var lowSaid = false
        for (e in a.events) {
            if (e.cancel) continue
            val kind = when (e.kind) {
                Alerts.Kind.OUTAGE -> Kind.OUTAGE
                Alerts.Kind.LOW -> Kind.LOW.also { lowSaid = true }
                Alerts.Kind.FULL -> Kind.FULL
                Alerts.Kind.PROTECTION -> Kind.PROTECTION
                Alerts.Kind.CELLS -> Kind.CELLS
                Alerts.Kind.TEMP -> Kind.TEMP
                Alerts.Kind.NO_DATA, Alerts.Kind.CHARGING -> null
            } ?: continue
            out += Msg(kind, e.title, e.text, battery)
        }
        n = empty(n, s, battery, out)
        n = step(n, s, battery, out, quiet = lowSaid)
        n = jump(n, s, t, battery, out)
        return Step(n, out)
    }

    /** Сторож не дістав батарею. [lastOkAt] — останній знімок звідки завгодно; null — ще не було. */
    fun onNoData(st: State, lastOkAt: Long?, now: Long, battery: String, tz: TimeZone = TimeZone.getDefault()): Step {
        if (lastOkAt == null || st.noData || now - lastOkAt < NO_DATA_MS) return Step(st, emptyList())
        val m = Msg(Kind.NO_DATA, tr("Сторож не бачить батарею з ${Background.hhmm(lastOkAt, tz)}",
                "The guard has not seen the battery since ${Background.hhmm(lastOkAt, tz)}"), tr(
            "Bluetooth не дістає: батарею вимкнули, вона зайнята іншим телефоном, або сторож далеко. Напишу, щойно побачу знову.",
            "Bluetooth cannot reach it: the battery is off, another phone is using it, or the guard is far. I will write when I see it again.",
        ), battery)
        return Step(st.copy(noData = true), listOf(m))
    }

    private fun empty(st: State, s: BatterySnapshot, battery: String, out: MutableList<Msg>): State {
        if (st.empty) return if (s.soc >= EMPTY_RESET) st.copy(empty = false) else st
        if (s.soc > EMPTY_SOC || s.flow == BatterySnapshot.Flow.CHARGING) return st
        out += Msg(Kind.LOW, tr("Батарея розрядилась — ${s.soc} %", "Battery is empty — ${s.soc} %"), tr(
            "Плата скоро вимкне розряд (або вже вимкнула). Потрібна зарядка: світло або генератор.",
            "The BMS will turn off discharge soon (or already has). It needs charging: grid or generator.",
        ), battery)
        return st.copy(empty = true)
    }

    private fun step(st: State, s: BatterySnapshot, battery: String, out: MutableList<Msg>, quiet: Boolean): State {
        val d = (s.soc.coerceIn(0, 100) / 10) * 10
        if (st.step < 0) return st.copy(step = d)
        if (d < st.step) {
            if (s.flow != BatterySnapshot.Flow.CHARGING && !quiet) {
                out += Msg(Kind.STEP, tr("Заряд ${s.soc} %", "Charge ${s.soc} %"), stepText(s), battery)
            }
            return st.copy(step = d)
        }
        if (s.soc >= st.step + STEP_RESET) return st.copy(step = d)
        return st
    }

    fun stepText(s: BatterySnapshot): String {
        val parts = ArrayList<String>()
        if (s.flow == BatterySnapshot.Flow.DISCHARGING) {
            parts += tr("віддає ${Fmt.watts(abs(s.power))}", "discharging at ${Fmt.watts(abs(s.power))}")
            TimeLeft.discharge(s.remainAh, s.fullAh, s.current)?.let { d ->
                val to = d.toThreshold.firstOrNull { it.first < s.soc && it.second != null }
                val h = to?.second
                parts += if (to != null && h != null) tr("до ${to.first} % ≈ ${Fmt.duration(h)}", "to ${to.first} % ≈ ${Fmt.duration(h)}")
                    else tr("до кінця ≈ ${Fmt.duration(d.totalH)}", "until empty ≈ ${Fmt.duration(d.totalH)}")
            }
        } else {
            parts += tr("стоїть", "idle")
        }
        parts += Fmt.volts(s.voltage)
        return parts.joinToString(" · ")
    }

    private fun jump(st: State, s: BatterySnapshot, t: Long, battery: String, out: MutableList<Msg>): State {
        val temp = s.temperatures.maxOrNull() ?: Double.NaN
        val delta = s.deltaMv ?: -1
        fun fresh(at: Long) = at > 0 && t - at in 0..JUMP_WINDOW_MS
        val reasons = ArrayList<String>()
        var mins = 0L
        if (!temp.isNaN() && !st.baseTemp.isNaN() && fresh(st.tempAt) && temp - st.baseTemp >= JUMP_TEMP_C) {
            reasons += tr("температура ${Fmt.celsius(st.baseTemp)} → ${Fmt.celsius(temp)}",
                "temperature ${Fmt.celsius(st.baseTemp)} → ${Fmt.celsius(temp)}")
            mins = maxOf(mins, (t - st.tempAt) / 60_000L)
        }
        if (delta >= 0 && st.baseDelta >= 0 && fresh(st.deltaAt) && delta - st.baseDelta >= JUMP_DELTA_MV) {
            reasons += tr("різниця комірок ${Fmt.millivolts(st.baseDelta)} → ${Fmt.millivolts(delta)}",
                "cell difference ${Fmt.millivolts(st.baseDelta)} → ${Fmt.millivolts(delta)}")
            mins = maxOf(mins, (t - st.deltaAt) / 60_000L)
        }
        var n = st
        if (reasons.isNotEmpty() && t - st.jumpAt >= JUMP_QUIET_MS) {
            val m = mins.coerceAtLeast(1)
            out += Msg(Kind.JUMP, tr("Різкий стрибок за $m хв", "Sudden jump in $m min"),
                reasons.joinToString("; ") + ". " + tr("Глянь на батарею, якщо можеш.", "Have a look at the battery if you can.") +
                    "\n" + short(s), battery)
            n = n.copy(jumpAt = t)
        }
        if (!temp.isNaN() && (!fresh(st.tempAt) || st.baseTemp.isNaN() || temp < st.baseTemp)) n = n.copy(tempAt = t, baseTemp = temp)
        if (delta >= 0 && (!fresh(st.deltaAt) || st.baseDelta < 0 || delta < st.baseDelta)) n = n.copy(deltaAt = t, baseDelta = delta)
        return n
    }

    /** Коротко про батарею: «87 % · стоїть · 13,31 В · 15,2 °C». */
    fun short(s: BatterySnapshot): String {
        val parts = arrayListOf("${s.soc} %")
        parts += when (s.flow) {
            BatterySnapshot.Flow.CHARGING -> tr("заряджається ${Fmt.watts(abs(s.power))}", "charging ${Fmt.watts(abs(s.power))}")
            BatterySnapshot.Flow.DISCHARGING -> tr("віддає ${Fmt.watts(abs(s.power))}", "discharging ${Fmt.watts(abs(s.power))}")
            BatterySnapshot.Flow.IDLE -> tr("стоїть", "idle")
        }
        parts += Fmt.volts(s.voltage)
        s.temperatures.maxOrNull()?.let { parts += Fmt.celsius(it) }
        s.deltaMv?.let { parts += tr("різниця ${Fmt.millivolts(it)}", "difference ${Fmt.millivolts(it)}") }
        return parts.joinToString(" · ")
    }

    // ---------- щоденне «я працюю» ----------

    /** День як число 20261021 — у місцевому часі. */
    fun dayKey(ms: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /** Чи пора слати «я працюю»: сьогодні ще не слали і вже [hour]:00 або пізніше. */
    fun dailyDue(lastDay: Int, now: Long, hour: Int, tz: TimeZone = TimeZone.getDefault()): Boolean {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        return dayKey(now, tz) != lastDay && c.get(Calendar.HOUR_OF_DAY) >= hour
    }

    /** Що відомо про одну батарею для «я працюю» і «/стан». */
    data class Seen(val name: String, val snapshot: BatterySnapshot?, val at: Long?)

    fun daily(batteries: List<Seen>, now: Long, tz: TimeZone = TimeZone.getDefault()): Msg =
        Msg(Kind.DAILY, tr("Сторож працює · ${Background.hhmm(now, tz)}", "The guard is working · ${Background.hhmm(now, tz)}"),
            status(batteries, now, tz) + "\n" + tr("Не прийшло завтра — зі сторожем щось сталося (вимкнувся, нема інтернету).",
                "If this does not come tomorrow, something happened to the guard (off, no internet)."))

    fun status(batteries: List<Seen>, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        if (batteries.isEmpty()) return tr("Батарей для сторожа ще нема — на телефоні-сторожі увімкни «Запис у фоні».",
            "No batteries for the guard yet — turn on \"Background recording\" on the guard phone.")
        return batteries.joinToString("\n") { b ->
            val s = b.snapshot
            val at = b.at
            when {
                s == null || at == null -> "🔋 ${b.name}: " + tr("даних ще нема", "no data yet")
                now - at > NO_DATA_MS -> "🔋 ${b.name}: " + tr("не бачу з ${Background.hhmm(at, tz)}; тоді було ${s.soc} %",
                    "not seen since ${Background.hhmm(at, tz)}; it was ${s.soc} % then")
                else -> "🔋 ${b.name}: ${short(s)} · " + tr("о ${Background.hhmm(at, tz)}", "at ${Background.hhmm(at, tz)}")
            }
        }
    }
}

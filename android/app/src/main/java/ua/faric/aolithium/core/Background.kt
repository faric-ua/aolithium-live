package ua.faric.aolithium.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Запис у фоні (v0.2.5, TODO T3): поки застосунок закритий, раз на [EVERY_MS] коротко підключитись до батареї,
 * прочитати один знімок, записати в історію й відпустити батарею (щоб Aolithium на іншому телефоні теж міг
 * підключитись). Навіщо — faric 19.10: відключення світла бувають, коли застосунок закритий.
 *
 * Тут — лише правила й тексти без Android (перевіряються тестами `BackgroundTest`).
 * Сама служба — `bg/RecordService.kt`.
 */
object Background {
    /** Як часто читати. Уночі (телефон лежить) Android будить застосунки рідше — буває до ~10 хв. */
    const val EVERY_MS = 5 * 60_000L

    /** Скільки чекати один знімок: пошук (до 10 с) + підключення + одна відповідь плати. Далі — «нема зв'язку». */
    const val READ_TIMEOUT_MS = 45_000L

    /** Струм ближче до нуля — «стоїть» (ні заряду, ні розряду). */
    const val IDLE_A = 0.2

    /** Результат останнього читання у фоні (зберігається в пам'яті телефона для «Ще» і сповіщення). */
    data class Result(val at: Long, val ok: Boolean, val soc: Int = 0, val current: Double = 0.0, val note: String = "")

    /** Чому цього разу не читали чи не прочитали. */
    enum class Skip(val text: String) {
        SCREEN_OPEN("відкритий екран батареї сам записує"),
        NO_PERMISSION("Android не дав дозволу на Bluetooth"),
        BLUETOOTH_OFF("Bluetooth вимкнено"),
        NO_ANSWER("нема зв'язку — батарея далеко чи зайнята іншим телефоном"),
    }

    /** Що зробити на черговому «тіку» з однією батареєю. */
    fun decide(screenHoldsIt: Boolean, permissionOk: Boolean, bluetoothOn: Boolean): Skip? = when {
        screenHoldsIt -> Skip.SCREEN_OPEN
        !permissionOk -> Skip.NO_PERMISSION
        !bluetoothOn -> Skip.BLUETOOTH_OFF
        else -> null
    }

    /** «віддає 4,10 А» / «бере 8,50 А» / «стоїть». */
    fun flow(current: Double): String = when {
        current <= -IDLE_A -> "віддає ${Fmt.amps(abs(current))}"
        current >= IDLE_A -> "бере ${Fmt.amps(current)}"
        else -> "стоїть"
    }

    /** Рядок сповіщення для однієї батареї: «Aolithium: 64 % · віддає 4,10 А · 04:35». */
    fun line(name: String, r: Result?, tz: TimeZone = TimeZone.getDefault()): String {
        if (r == null) return "$name: чекаю першого запису"
        val t = hhmm(r.at, tz)
        return if (r.ok) "$name: ${r.soc} % · ${flow(r.current)} · $t"
        else "$name: ${r.note.ifBlank { Skip.NO_ANSWER.text }} · $t"
    }

    /** Для «Ще»: «04:35 — 64 %, віддає 4,10 А» або «04:35 — нема зв'язку …». */
    fun status(r: Result?, tz: TimeZone = TimeZone.getDefault()): String {
        if (r == null) return "ще не було"
        val t = hhmm(r.at, tz)
        return if (r.ok) "$t — ${r.soc} %, ${flow(r.current)}" else "$t — ${r.note.ifBlank { Skip.NO_ANSWER.text }}"
    }

    /** Коли наступний тік: від початку попереднього, але не раніше ніж за хвилину від зараз. */
    fun nextAt(lastTickAt: Long, now: Long): Long = maxOf(lastTickAt + EVERY_MS, now + 60_000L)

    fun hhmm(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = tz }.format(Date(ms))
}

/**
 * Збереження історії у файл CSV (TODO T3: «експорт CSV»): зрозумілі назви колонок і дата людською мовою,
 * щоб файл відкривався в таблицях. Розділювач — кома, числа — з крапкою (так таблиці читають CSV).
 */
object HistoryCsv {
    const val HEADER = "дата_час,мітка_мс,заряд_%,напруга_В,струм_А,потужність_Вт,залишок_Агод,темп_мін_С,темп_макс_С,різниця_мВ,комірки_мВ"

    fun row(p: HistoryPoint, tz: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = tz }.format(Date(p.t))
        fun f(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)
        return listOf(
            date, p.t.toString(), p.soc.toString(), f(p.voltage, 2), f(p.current, 2), f(p.power, 1), f(p.remainAh, 2),
            p.tempMin?.let { f(it, 1) } ?: "", p.tempMax?.let { f(it, 1) } ?: "", p.deltaMv?.toString() ?: "",
            p.cellsMv.joinToString(";"),
        ).joinToString(",")
    }

    /** Назва файлу: «aolithium-AL12-100-4S-2026-10-19.csv» (без адреси батареї). */
    fun fileName(title: String, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val safe = title.replace(Regex("[^A-Za-z0-9А-Яа-яІіЇїЄєҐґ_-]+"), "-").trim('-').take(30).ifBlank { "battery" }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = tz }.format(Date(now))
        return "aolithium-$safe-$day.csv"
    }
}

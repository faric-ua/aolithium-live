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

    /**
     * Скільки чекати один знімок: до 3 спроб (T46, кожна повторна — зі свіжим пошуком до 10 с) + підключення + одна
     * відповідь плати. Далі — «нема зв'язку». Було 45 с на 2 спроби без пошуку.
     */
    const val READ_TIMEOUT_MS = 75_000L

    /** Струм ближче до нуля — «стоїть» (ні заряду, ні розряду). */
    const val IDLE_A = 0.2

    /** Результат останнього читання у фоні (зберігається в пам'яті телефона для «Ще» і сповіщення). */
    data class Result(val at: Long, val ok: Boolean, val soc: Int = 0, val current: Double = 0.0, val note: String = "")

    /**
     * Чому цього разу не читали чи не прочитали. [text] — обома мовами (T12), через `get()`.
     * T12: у пам'ять телефона ([Result.note], `data/BgSettings`) іде не текст, а назва ([name], напр. «NO_ANSWER») —
     * текст поточною мовою дає [localize] при показі. Старий запис українським текстом показуємо як є
     * (за ~5 хв його замінить новий).
     */
    enum class Skip {
        SCREEN_OPEN, NO_PERMISSION, BLUETOOTH_OFF, NO_ANSWER,

        /**
         * T81 (faric 25.10: «щоб ти писав причину відсутності даних, конкретну»): замість одного «нема зв'язку» — чому саме.
         * [LOCATION_OFF] — Android 8–11 без геолокації пошук мовчки нічого не бачить (мамин телефон — Android 10).
         */
        LOCATION_OFF, NOT_SEEN, WEAK, NO_CONNECT, NO_REPLY;

        /** [arg] — для [WEAK] і [NO_CONNECT]: сигнал, dBm. */
        fun text(arg: Int? = null): String = when (this) {
            SCREEN_OPEN -> tr("відкритий екран батареї сам записує", "the open battery screen records by itself")
            NO_PERMISSION -> tr("Android не дав дозволу на Bluetooth", "Android did not allow Bluetooth")
            BLUETOOTH_OFF -> tr("Bluetooth вимкнено", "Bluetooth is off")
            NO_ANSWER -> tr(
                "нема зв'язку — батарея далеко чи зайнята іншим телефоном",
                "no connection — battery far away or used by another phone",
            )
            LOCATION_OFF -> tr(
                "геолокація вимкнена — на Android 11 і старіших без неї пошук Bluetooth нічого не бачить",
                "location is off — on Android 11 and older Bluetooth search sees nothing without it",
            )
            NOT_SEEN -> tr(
                "батареї не видно в ефірі — телефон далеко, батарея вимкнена або з'єднана з іншим телефоном",
                "the battery is not on the air — the phone is far, the battery is off or connected to another phone",
            )
            WEAK -> tr(
                "батарею видно, але сигнал слабкий (${arg ?: "?"} dBm) — далеко або стіна",
                "the battery is visible, but the signal is weak (${arg ?: "?"} dBm) — too far or a wall",
            )
            NO_CONNECT -> tr(
                "батарею видно (${arg ?: "?"} dBm), але не підключається — Bluetooth телефона не дав з'єднання",
                "the battery is visible (${arg ?: "?"} dBm), but it does not connect — the phone's Bluetooth refused",
            )
            NO_REPLY -> tr("підключився, але плата не відповіла", "connected, but the BMS did not reply")
        }

        /** Без числа — як було (T12: getter, щоб після перемикання мови — новою мовою). */
        val text: String get() = text(null)

        /** Що класти в пам'ять: «WEAK:-94» (назва й число), щоб показати поточною мовою ([localize]). */
        fun note(arg: Int? = null): String = if (arg == null) name else "$name:$arg"

        companion object {
            /** Збережена причина («NAME» чи «NAME:число») — текстом поточною мовою; незнайомий запис — як є. */
            fun localize(note: String): String {
                val name = note.substringBefore(':')
                val arg = note.substringAfter(':', "").toIntOrNull()
                return entries.firstOrNull { it.name == name }?.text(arg) ?: note
            }

            /** Причина — це «телефон не дістав батарею» (для швидкого повтору, T55), а не «свідомо пропустили». */
            fun isNoLink(note: String): Boolean = note.substringBefore(':') in setOf(
                NO_ANSWER.name, NOT_SEEN.name, WEAK.name, NO_CONNECT.name, NO_REPLY.name,
            )
        }
    }

    /** Що зробити на черговому «тіку» з однією батареєю. [locationOn] — для Android 8–11 (T81); новіші — завжди true. */
    fun decide(screenHoldsIt: Boolean, permissionOk: Boolean, bluetoothOn: Boolean, locationOn: Boolean = true): Skip? = when {
        screenHoldsIt -> Skip.SCREEN_OPEN
        !permissionOk -> Skip.NO_PERMISSION
        !bluetoothOn -> Skip.BLUETOOTH_OFF
        !locationOn -> Skip.LOCATION_OFF
        else -> null
    }

    /** Слабше за це — «сигнал слабкий» (той самий поріг, що й підказка «ти далеко» в пошуку). */
    const val WEAK_DBM = Signal.TOO_FAR

    /**
     * T81: чому не прочитали — з того, що бачили за час спроби: чи батарея була в ефірі ([seen]), з яким сигналом ([rssi]),
     * і чи вдалось підключитись ([connected]). Повертає те, що класти в [Result.note].
     */
    fun why(seen: Boolean, rssi: Int?, connected: Boolean): String = when {
        connected -> Skip.NO_REPLY.note()
        !seen -> Skip.NOT_SEEN.note()
        rssi != null && rssi <= WEAK_DBM -> Skip.WEAK.note(rssi)
        else -> Skip.NO_CONNECT.note(rssi)
    }

    // ---------- T81: ловити момент ----------

    /**
     * T81 (faric 25.10: у мами через стіну 3 м телефон не бачить батарею, даних нема півдоби): поки працює запис у фоні,
     * телефон тихо слухає ефір (пошук з фільтром лише на свої батареї, найощадливіший режим). Щойно батарея з'явилась
     * з добрим сигналом, а даних давно нема — читаємо одразу, не чекаючи 5-хвилинного тіку. Розклад раз на 5 хв лишається.
     */
    /** «Давно нема даних» — від цього (faric: «віджет оновили хоча б раз на 10 хв»). */
    const val CATCH_AFTER_MS = 10 * 60_000L
    /** Сигнал, з яким уже варто пробувати (трохи слабший за [WEAK_DBM]: у мами краще −90, ніж нічого). */
    const val CATCH_MIN_DBM = -90
    /** Не частіше за це — щоб на межі дальності не смикати Bluetooth щохвилини. */
    const val CATCH_COOLDOWN_MS = 2 * 60_000L

    fun shouldCatch(now: Long, rssi: Int, lastOkAt: Long?, lastCatchAt: Long, busy: Boolean): Boolean =
        !busy && rssi >= CATCH_MIN_DBM && now - lastCatchAt >= CATCH_COOLDOWN_MS &&
            (lastOkAt == null || now - lastOkAt >= CATCH_AFTER_MS)

    /** Скільки писати в журнал про «бачу в ефірі»: раз на [SIGHT_LOG_MS] або коли сигнал перейшов в інший рівень. */
    const val SIGHT_LOG_MS = 5 * 60_000L

    fun logSighting(now: Long, lastLoggedAt: Long, rssi: Int, lastLoggedRssi: Int?): Boolean =
        lastLoggedRssi == null || now - lastLoggedAt >= SIGHT_LOG_MS || Signal.level(rssi) != Signal.level(lastLoggedRssi)

    /** «віддає 4,10 А» / «бере 8,50 А» / «стоїть». */
    fun flow(current: Double): String = when {
        current <= -IDLE_A -> tr("віддає ${Fmt.amps(abs(current))}", "discharging ${Fmt.amps(abs(current))}")
        current >= IDLE_A -> tr("бере ${Fmt.amps(current)}", "charging ${Fmt.amps(current)}")
        else -> tr("стоїть", "idle")
    }

    /**
     * Рядок сповіщення для однієї батареї: «Aolithium: 64 % · віддає 4,10 А · 04:35».
     * [withTime] = false — без часу в кінці (T21, faric 19.10 20:55: час — у верхньому рядку сповіщення, праворуч).
     */
    fun line(name: String, r: Result?, tz: TimeZone = TimeZone.getDefault(), withTime: Boolean = true): String {
        if (r == null) return tr("$name: чекаю першого запису", "$name: waiting for the first record")
        val t = if (withTime) " · ${hhmm(r.at, tz)}" else ""
        return if (r.ok) "$name: ${r.soc} % · ${flow(r.current)}$t"
        else "$name: ${Skip.localize(r.note.ifBlank { Skip.NO_ANSWER.name })}$t"
    }

    /** Для «Ще»: «04:35 — 64 %, віддає 4,10 А» або «04:35 — нема зв'язку …». */
    fun status(r: Result?, tz: TimeZone = TimeZone.getDefault()): String {
        if (r == null) return tr("ще не було", "not yet")
        val t = hhmm(r.at, tz)
        return if (r.ok) "$t — ${r.soc} %, ${flow(r.current)}" else "$t — ${Skip.localize(r.note.ifBlank { Skip.NO_ANSWER.name })}"
    }

    /**
     * T40 (v0.4.9, faric 20.10 «підтримую»): розблокував телефон — одне коротке читання, щоб віджет і смужка заряду
     * показали свіже. Лише якщо останнє читання старше за [UNLOCK_MIN_MS] і зараз нічого не читаємо — інакше часті
     * розблокування ганяли б Bluetooth (і займали батарею для Aolithium на іншому телефоні) щохвилини.
     */
    const val UNLOCK_MIN_MS = 60_000L

    fun readOnUnlock(lastTickAt: Long, now: Long, busy: Boolean): Boolean = !busy && now - lastTickAt >= UNLOCK_MIN_MS

    /**
     * T55 (журнал faric 21.10 12:45–14:10): на межі дальності половина спроб — код 133, а наступна вдала часто вже за
     * кілька хвилин. Після невдачі — ще раз через [RETRY_MS], але лише [QUICK_RETRIES] рази поспіль: коли телефон
     * справді далеко (на роботі), фон не ганяє Bluetooth удвічі частіше весь день.
     */
    const val RETRY_MS = 2 * 60_000L
    const val QUICK_RETRIES = 2

    /** Чи наступний тік — швидкий повтор: [failStreak] — скільки тіків поспіль закінчились «нема зв'язку». */
    fun retrySoon(failStreak: Int): Boolean = failStreak in 1..QUICK_RETRIES

    /** Коли наступний тік: від початку попереднього, але не раніше ніж за хвилину від зараз. */
    fun nextAt(lastTickAt: Long, now: Long, retrySoon: Boolean = false): Long =
        maxOf(lastTickAt + (if (retrySoon) RETRY_MS else EVERY_MS), now + 60_000L)

    fun hhmm(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = tz }.format(Date(ms))
}

/**
 * Збереження історії у файл CSV (TODO T3: «експорт CSV»): зрозумілі назви колонок і дата людською мовою,
 * щоб файл відкривався в таблицях. Розділювач — кома, числа — з крапкою (так таблиці читають CSV).
 */
object HistoryCsv {
    /** Назви колонок файлу — не переклад, а ключі (T12): однакові обома мовами. */
    const val HEADER = "дата_час,мітка_мс,заряд_%,напруга_В,струм_А,потужність_Вт,залишок_Агод," + // uk-only
        "темп_мін_С,темп_макс_С,різниця_мВ,комірки_мВ" // uk-only

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
        val letters = "[^A-Za-z0-9А-Яа-яІіЇїЄєҐґ_-]+" // uk-only: не текст, а дозволені букви
        val safe = title.replace(Regex(letters), "-").trim('-').take(30).ifBlank { "battery" }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = tz }.format(Date(now))
        return "aolithium-$safe-$day.csv"
    }
}

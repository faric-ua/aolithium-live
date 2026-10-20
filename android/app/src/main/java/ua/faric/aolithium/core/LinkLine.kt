package ua.faric.aolithium.core

/**
 * Рядок «Зв'язок» угорі екрана батареї (T25, faric 19.10 22:12).
 *
 * Що було: картка «Зв'язок» під заголовком щоразу міняла висоту — «Читаю плату…» (2 рядки + «Нижче — останні дані»),
 * «Зв'язок втрачено» (6 рядків + 2 кнопки), «Підключаюсь…» (2 рядки), а на зв'язку зникала зовсім. Далеко від батареї
 * (−90 dBm) стани міняються кожні кілька секунд — і весь спідометр під карткою їздив вгору-вниз.
 *
 * Як тепер (той самий урок, що T19 — «каркас сталої форми»): **завжди два рядки по одному рядку тексту**, на всіх
 * станах однакові. Довге пояснення й кнопка «Журнал зв'язку» — у вікні за торканням рядка. Кнопка «Ще раз» має
 * завжди зарезервоване місце (лише ховається), тож і вона висоту не міняє.
 *
 * Тут лише слова й колір — без Android, щоб перевіряти тестами (`LinkLineTest`).
 */
object LinkLine {
    /** Те саме, що `LinkStatus.Phase` (той у ble/, з Android поруч), — щоб ядро не залежало від Bluetooth. */
    enum class State { IDLE, CONNECTING, READING, LIVE, NO_ANSWER, LOST, FAILED }

    enum class Tone { GOOD, INFO, WARN, BAD, NEUTRAL }

    /**
     * [pill] — слово в «пігулці» (стан), [detail] — коротко праворуч від неї, [second] — другий рядок (чиї числа нижче),
     * [retry] — показати кнопку «Ще раз» (місце під неї є завжди).
     */
    data class Model(val pill: String, val detail: String, val second: String, val tone: Tone, val retry: Boolean)

    /**
     * [attempt] — котрий це повтор (0 — перша спроба), [ofAttempts] — скільки повторів буде всього.
     * [message] — повне пояснення від зв'язку (у рядок іде лише перше речення, решта — у вікні).
     * [liveAt] — коли прийшли живі дані в цьому сеансі (0 — ще не було), [savedAt] / [savedStamp] — останні збережені
     * з минулих разів (коли й як написати час, «19.10 22:04»), [rssi] — сигнал з пошуку, якщо відомий.
     */
    fun build(
        state: State,
        attempt: Int = 0,
        ofAttempts: Int = 0,
        message: String = "",
        rssi: Int? = null,
        liveAt: Long = 0L,
        liveStamp: String = "",
        savedAt: Long = 0L,
        savedStamp: String = "",
        now: Long,
        demo: Boolean = false,
    ): Model {
        val signal = rssi?.let { Signal.describe(it) }
        val repeat = if (attempt > 0 && ofAttempts > 0) tr("повтор $attempt з $ofAttempts", "retry $attempt of $ofAttempts") else ""
        val detail = when (state) {
            State.LIVE -> if (demo) tr("демо", "demo") else tr("дані наживо", "live data")
            State.CONNECTING -> join(repeat.ifEmpty { tr("зазвичай 2–5 с", "usually 2–5 s") }, signal)
            State.READING -> tr("читаю дані плати", "reading BMS data")
            State.LOST -> join(repeat.ifEmpty { tr("пробую ще раз", "trying again") }, signal)
            // Далеко — головне, що треба знати; інакше — перше речення пояснення (без повтору слова з пігулки).
            State.NO_ANSWER, State.FAILED -> if (rssi != null && Signal.tooFar(rssi)) {
                tr("батарея далеко · $signal", "battery far away · $signal")
            } else {
                firstSentence(message).removePrefix(pillOf(state)).trim().ifEmpty { tr("торкнись — що робити", "tap — what to do") }
            }
            State.IDLE -> join(tr("торкнись «Ще раз»", "tap \"Retry\""), null)
        }
        val (at, stamp) = if (liveAt > 0L) liveAt to liveStamp else savedAt to savedStamp
        val second = when {
            state == State.LIVE && liveAt > 0L -> tr("оновлено ", "updated ") + Fmt.ago(now - liveAt)
            at > 0L -> tr("нижче — дані від $stamp (${Fmt.ago(now - at)})", "below — data from $stamp (${Fmt.ago(now - at)})")
            else -> tr("даних ще нема — замість чисел прочерки", "no data yet — dashes instead of numbers")
        }
        val tone = when (state) {
            State.LIVE -> Tone.GOOD
            State.CONNECTING, State.READING -> Tone.INFO
            State.LOST, State.NO_ANSWER -> Tone.WARN
            State.FAILED -> Tone.BAD
            State.IDLE -> Tone.NEUTRAL
        }
        val retry = state == State.IDLE || state == State.LOST || state == State.NO_ANSWER || state == State.FAILED
        return Model(pillOf(state), detail, second, tone, retry)
    }

    fun pillOf(state: State): String = when (state) {
        State.IDLE -> tr("Не підключено", "Not connected")
        State.CONNECTING -> tr("Підключаюсь…", "Connecting…")
        State.READING -> tr("Читаю плату…", "Reading BMS…")
        State.LIVE -> tr("На зв'язку", "Connected")
        State.NO_ANSWER -> tr("Плата мовчить", "BMS is silent")
        State.LOST -> tr("Зв'язок втрачено", "Connection lost")
        State.FAILED -> tr("Не вдалось", "Failed")
    }

    /** «Bluetooth вимкнено. Увімкни його…» → «Bluetooth вимкнено». Без крапки в кінці — рядок короткий. */
    fun firstSentence(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        val end = Regex("""[.!?…](\s|$)""").find(t)?.range?.first ?: t.length
        return t.substring(0, end).trim()
    }

    private fun join(a: String, b: String?): String = if (b.isNullOrEmpty()) a else "$a · $b"
}

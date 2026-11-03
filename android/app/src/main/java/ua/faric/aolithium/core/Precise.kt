package ua.faric.aolithium.core

/**
 * T91 «Точний запис» (faric 03.11): поки увімкнено — в історію йде кожне читання плати (~раз на 2 с), а не раз на 10 с
 * (відкритий екран) чи раз на 5 хв (запис у фоні). Тоді на графіках видно справжні стрибки струму, а не здогадки
 * між рідкими точками.
 *
 * Правила (лише тут, без Android — щоб перевірити тестом):
 * - пише й тоді, коли застосунок згорнули чи відкрили інший;
 * - екран погас / телефон заблоковано — пише ще [LOCKED_MAX_MS] (5 хв), потім вимикається сам: батарея вільна,
 *   Bluetooth не тримається всю ніч;
 * - розблокував раніше — відлік скидається;
 * - закрив застосунок — вимкнено (і після перезапуску теж вимкнено: стан лише в пам'яті, не забудеш увімкненим).
 */
object Precise {
    /** Пишемо кожне читання: плата читається раз на 2 с, з запасом на розкид часу. */
    const val EVERY_MS = 1_500L

    /** Екран вимкнено — ще стільки пишемо, потім «Точний запис» вимикається сам (faric: «хоча б 5 хвилин»). */
    const val LOCKED_MAX_MS = 5 * 60_000L

    /** Як часто служба перевіряє правила. */
    const val CHECK_MS = 5_000L

    /** Чому запис зупинився. */
    enum class Stop {
        /** Сам вимкнув (кнопка в застосунку чи в сповіщенні). */
        USER,

        /** Екран був вимкнений [LOCKED_MAX_MS]. */
        LOCKED,

        /** Закрив застосунок. */
        CLOSED,

        /** Відкрив іншу батарею — плата одна, зв'язок один. */
        OTHER,

        /** Android не дав запустити службу (немає дозволу тощо). */
        FAILED;

        val text: String get() = when (this) {
            USER -> tr("вимкнено", "turned off")
            LOCKED -> tr("екран був вимкнений 5 хв", "the screen was off for 5 min")
            CLOSED -> tr("застосунок закрили", "the app was closed")
            OTHER -> tr("відкрили іншу батарею", "another battery was opened")
            FAILED -> tr("телефон не дав запустити", "the phone did not allow it")
        }
    }

    /** Чи час зупинитись: null — пишемо далі. [screenOffSince] — коли погас екран (null — екран увімкнено й розблоковано). */
    fun stopReason(now: Long, screenOffSince: Long?): Stop? =
        if (screenOffSince != null && now - screenOffSince >= LOCKED_MAX_MS) Stop.LOCKED else null

    /** Коли вимкнеться сам (екран погас), або null — поки екран увімкнено, не вимкнеться. */
    fun stopsAt(screenOffSince: Long?): Long? = screenOffSince?.let { it + LOCKED_MAX_MS }

    /** Як часто писати в історію: точний запис для цієї батареї — кожне читання, інакше — звичайні 10 с. */
    fun recordEvery(preciseOn: Boolean): Long = if (preciseOn) EVERY_MS else HistoryMath.RECORD_EVERY_MS
}

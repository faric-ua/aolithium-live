package ua.faric.aolithium.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T69 (faric 22.10 05:36 і 17:53): батарея не на зв'язку — ми не знаємо, що з нею зараз. Не показувати старі числа так,
 * ніби вони живі («2,54 А ↓ бере заряд» десять хвилин тому — обман). Спідометр кожні [BLINK_MS] міняється:
 * «— % · немає зв'язку» ↔ останні дані сірим з підписом «дані від HH:MM». Миттєве (струм, вати, «вистачить на»,
 * стан ключів) у картках — прочерк. Тут — без Android: фаза і підпис.
 */
object StaleShow {
    const val BLINK_MS = 3_000L

    /** Перші 3 с — «немає зв'язку», наступні 3 с — останні дані, і так по колу. */
    fun showOld(uptimeMs: Long): Boolean = (uptimeMs / BLINK_MS) % 2L == 1L

    /** Скільки до наступної зміни — щоб перемалювати рівно тоді. */
    fun msToSwitch(uptimeMs: Long): Long = BLINK_MS - uptimeMs % BLINK_MS

    /** «дані від 05:26»; не сьогодні — «дані від 21.10 18:20». */
    fun label(at: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = tz }
        val sameDay = day.format(Date(at)) == day.format(Date(now))
        val stamp = SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM HH:mm", Locale.ROOT).apply { timeZone = tz }.format(Date(at))
        return tr("дані від $stamp", "data from $stamp")
    }
}

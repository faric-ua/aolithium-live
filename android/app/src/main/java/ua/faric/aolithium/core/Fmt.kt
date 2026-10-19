package ua.faric.aolithium.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Числа для екрана — українською: кома замість крапки, одиниці кирилицею. */
object Fmt {
    fun num(v: Double, digits: Int): String {
        val s = String.format(Locale.ROOT, "%.${digits}f", v).replace('.', ',')
        return if (s.startsWith("-") && s.trim('-', '0', ',').isEmpty()) s.substring(1) else s // «-0,00» → «0,00»
    }

    fun volts(v: Double, digits: Int = 2) = num(v, digits) + " В"
    fun amps(v: Double) = num(v, 2) + " А"
    fun watts(v: Double) = num(v, if (abs(v) >= 100) 0 else 1) + " Вт"
    fun ampHours(v: Double) = num(v, 2) + " А·год"
    fun celsius(v: Double) = num(v, 1) + " °C"

    /** Час роботи плати JK: «40 діб 3 год», «5 год 12 хв», «12 хв». */
    fun runtime(sec: Long): String {
        val m = sec / 60
        val h = m / 60
        val d = h / 24
        return when {
            d > 0 -> "$d " + plural(d.toInt(), "доба", "доби", "діб") + " ${h % 24} год"
            h > 0 -> "$h год ${m % 60} хв"
            else -> "$m хв"
        }
    }
    fun millivolts(mv: Int) = "$mv мВ"
    fun cellVolts(mv: Int) = num(mv / 1000.0, 3) + " В"

    /** «3 год 20 хв», «45 хв», «понад 99 год». */
    fun duration(hours: Double): String {
        if (hours.isNaN() || hours < 0) return "—"
        if (hours > 99) return "понад 99 год"
        val totalMin = (hours * 60).roundToLong()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m хв"
            m == 0L -> "$h год"
            else -> "$h год $m хв"
        }
    }

    /** «щойно», «12 с тому», «3 хв тому». */
    fun ago(ms: Long): String {
        val s = (ms / 1000.0).roundToInt().coerceAtLeast(0)
        return when {
            s < 3 -> "щойно"
            s < 60 -> "$s с тому"
            s < 3600 -> "${s / 60} хв тому"
            else -> "${s / 3600} год тому"
        }
    }

    /** «1 цикл», «2 цикли», «5 циклів», «123 цикли». */
    fun cycles(n: Int): String = "$n " + plural(n, "цикл", "цикли", "циклів")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1 -> one
            b in 2..4 -> few
            else -> many
        }
    }
}

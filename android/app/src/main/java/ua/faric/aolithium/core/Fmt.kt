package ua.faric.aolithium.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Числа для екрана. Українською — кома замість крапки й одиниці кирилицею («13,31 В»); англійською (T12) — крапка
 * й латиниця («13.31 V»). Одиниці — тут, щоб ніде в коді не писати «В» / «V» окремо.
 */
object Fmt {
    fun num(v: Double, digits: Int): String {
        val dec = if (Lang.isEn) '.' else ','
        val s = String.format(Locale.ROOT, "%.${digits}f", v).replace('.', dec)
        return if (s.startsWith("-") && s.trim('-', '0', dec).isEmpty()) s.substring(1) else s // «-0,00» → «0,00»
    }

    /** Одиниці виміру: [V] «В / V», [A] «А / A», [W] «Вт / W», [AH] «А·год / Ah», [WH], [KWH], [MV] «мВ / mV», [H] «год / h», [MIN] «хв / min». */
    val V get() = tr("В", "V")
    val A get() = tr("А", "A")
    val W get() = tr("Вт", "W")
    val AH get() = tr("А·год", "Ah")
    val WH get() = tr("Вт·год", "Wh")
    val KWH get() = tr("кВт·год", "kWh")
    val MV get() = tr("мВ", "mV")
    val MOHM get() = tr("мОм", "mΩ")
    val H get() = tr("год", "h")
    val MIN get() = tr("хв", "min")
    val SEC get() = tr("с", "s")

    fun volts(v: Double, digits: Int = 2) = num(v, digits) + " " + V
    fun amps(v: Double) = num(v, 2) + " " + A
    fun watts(v: Double) = num(v, if (abs(v) >= 100) 0 else 1) + " " + W
    fun ampHours(v: Double) = num(v, 2) + " " + AH
    fun celsius(v: Double) = num(v, 1) + " °C"

    /** Час роботи плати JK: «40 діб 3 год», «5 год 12 хв», «12 хв». */
    fun runtime(sec: Long): String {
        val m = sec / 60
        val h = m / 60
        val d = h / 24
        return when {
            d > 0 -> "$d " + plural(d.toInt(), "доба", "доби", "діб", "day", "days") + " ${h % 24} $H"
            h > 0 -> "$h $H ${m % 60} $MIN"
            else -> "$m $MIN"
        }
    }
    fun millivolts(mv: Int) = "$mv $MV"
    fun cellVolts(mv: Int) = num(mv / 1000.0, 3) + " " + V

    /** «3 год 20 хв», «45 хв», «понад 99 год». */
    fun duration(hours: Double): String {
        if (hours.isNaN() || hours < 0) return "—"
        if (hours > 99) return tr("понад 99 год", "over 99 h")
        val totalMin = (hours * 60).roundToLong()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m $MIN"
            m == 0L -> "$h $H"
            else -> "$h $H $m $MIN"
        }
    }

    /** «щойно», «12 с тому», «3 хв тому». */
    fun ago(ms: Long): String {
        val s = (ms / 1000.0).roundToInt().coerceAtLeast(0)
        return when {
            s < 3 -> tr("щойно", "just now")
            s < 60 -> tr("$s с тому", "$s s ago")
            s < 3600 -> tr("${s / 60} хв тому", "${s / 60} min ago")
            else -> tr("${s / 3600} год тому", "${s / 3600} h ago")
        }
    }

    /** «1 цикл», «2 цикли», «5 циклів», «123 цикли». */
    fun cycles(n: Int): String = "$n " + plural(n, "цикл", "цикли", "циклів", "cycle", "cycles")

    /**
     * Слово до числа обома мовами: українською — три форми («1 цикл, 2 цикли, 5 циклів»), англійською — дві
     * («1 cycle, 2 cycles»).
     */
    fun plural(n: Int, one: String, few: String, many: String, enOne: String, enMany: String): String =
        if (Lang.isEn) (if (abs(n) == 1) enOne else enMany) else pluralUk(n, one, few, many)

    /** Лише українські форми (правило «1 / 2–4 / 5–20, 11–14»). Ззовні — лише версія з англійськими формами. */
    private fun pluralUk(n: Int, one: String, few: String, many: String): String {
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

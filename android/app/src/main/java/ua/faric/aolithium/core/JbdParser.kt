package ua.faric.aolithium.core

/**
 * Розбір даних з кадрів JBD у зрозумілі числа.
 * Номери байтів — від початку **даних** (після `DD код стан довжина`), усі числа — старший байт першим.
 */
object JbdParser {
    /** Найменше даних у відповіді «Основне», без якого розбирати нема чого (до кількості датчиків включно). */
    const val BASIC_MIN = 23

    /** Більше комірок у платі JBD не буває (балансування — 32 біти). */
    const val MAX_CELLS = 32

    /** Температура: (x − 2731) / 10 °C — тобто десяті частки кельвіна. */
    private const val KELVIN_X10 = 2731

    fun basic(data: ByteArray): JbdBasic? {
        if (data.size < BASIC_MIN) return null
        val mos = u8(data, 20)
        // Великі батареї (понад 655 А·год): біт 7 байта ключів — ємність і струм у 0,1, а не 0,01 (esphome-jbd-bms).
        val scale = if (mos and 0x80 != 0) 0.1 else 0.01
        val sensors = u8(data, 22)
        val available = (data.size - 23) / 2
        val temps = (0 until minOf(sensors, available)).map { i -> (u16(data, 23 + i * 2) - KELVIN_X10) / 10.0 }
        val balance = (u16(data, 14).toLong() shl 16) or u16(data, 12).toLong()
        return JbdBasic(
            voltage = u16(data, 0) * 0.01,
            current = s16(data, 2) * scale,
            remainAh = u16(data, 4) * scale,
            fullAh = u16(data, 6) * scale,
            cycles = u16(data, 8),
            made = Ymd.fromJbd(u16(data, 10)),
            balanceMask = balance,
            protectionMask = u16(data, 16),
            firmwareRaw = u8(data, 18),
            soc = u8(data, 19),
            chargeOn = mos and 0x01 != 0,
            dischargeOn = mos and 0x02 != 0,
            cellCount = minOf(u8(data, 21), MAX_CELLS),
            sensorCount = sensors,
            temperatures = temps,
        )
    }

    /** Напруги комірок у мілівольтах, по 2 байти на комірку. */
    fun cells(data: ByteArray): List<Int> = (0 until minOf(data.size / 2, MAX_CELLS)).map { u16(data, it * 2) }

    /** Назва плати — текст до першого невидимого символу (як у aiobmsble `b2str`). */
    fun hardware(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    /** Лічильники захистів за весь час (команда 0xAA): 11 чисел по 2 байти. */
    fun errorCounts(data: ByteArray): List<Pair<String, Int>>? {
        if (data.size < ERROR_COUNT_NAMES.size * 2) return null
        return ERROR_COUNT_NAMES.mapIndexed { i, name -> name to u16(data, i * 2) }
    }

    /**
     * Порядок — як у протоколі (esphome-jbd-bms `on_error_counts_data_`).
     * T12: це **ключі**, а не текст для екрана — зберігаються в пам'яті телефона (`data/LastSnapshot`) і за ними
     * шукає `Glossary.forCounter`, тож лишаються українськими обома мовами. Показувати — через [counterTitle].
     */
    val ERROR_COUNT_NAMES = listOf(
        "Коротке замикання", // uk-only: ключ
        "Завеликий струм заряду", // uk-only: ключ
        "Завеликий струм розряду", // uk-only: ключ
        "Перезаряд комірки", // uk-only: ключ
        "Переразряд комірки", // uk-only: ключ
        "Гаряче для заряду", // uk-only: ключ
        "Холодно для заряду", // uk-only: ключ
        "Гаряче для розряду", // uk-only: ключ
        "Холодно для розряду", // uk-only: ключ
        "Перезаряд батареї", // uk-only: ключ
        "Переразряд батареї", // uk-only: ключ
    )

    /** Той самий захист, що й лічильник з [ERROR_COUNT_NAMES] (порядок той самий) — його назва вже обома мовами. */
    private val ERROR_COUNT_PROTECTIONS = listOf(
        Protection.SHORT_CIRCUIT,
        Protection.CHARGE_OVERCURRENT,
        Protection.DISCHARGE_OVERCURRENT,
        Protection.CELL_OVER,
        Protection.CELL_UNDER,
        Protection.CHARGE_HOT,
        Protection.CHARGE_COLD,
        Protection.DISCHARGE_HOT,
        Protection.DISCHARGE_COLD,
        Protection.PACK_OVER,
        Protection.PACK_UNDER,
    )

    /** Назва лічильника для екрана поточною мовою (T12); незнайомий ключ — як є. */
    fun counterTitle(name: String): String = if (!Lang.isEn) name
    else ERROR_COUNT_NAMES.indexOf(name).takeIf { it >= 0 }?.let { ERROR_COUNT_PROTECTIONS[it].title } ?: name

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF
    private fun u16(d: ByteArray, i: Int) = (u8(d, i) shl 8) or u8(d, i + 1)
    private fun s16(d: ByteArray, i: Int) = u16(d, i).toShort().toInt()
}

/** Дата виготовлення (з плати). */
data class Ymd(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = "%02d.%02d.%d".format(day, month, year)

    companion object {
        /** JBD: 7 біт — рік від 2000, 4 біти — місяць, 5 біт — день. Нуль або нісенітниця — дати нема. */
        fun fromJbd(v: Int): Ymd? {
            val y = 2000 + (v shr 9)
            val m = (v shr 5) and 0x0F
            val d = v and 0x1F
            return if (v == 0 || m !in 1..12 || d !in 1..31) null else Ymd(y, m, d)
        }
    }
}

/** Відповідь «Основне» (0x03), як є в платі. */
data class JbdBasic(
    val voltage: Double,
    val current: Double,
    val remainAh: Double,
    val fullAh: Double,
    val cycles: Int,
    val made: Ymd?,
    val balanceMask: Long,
    val protectionMask: Int,
    val firmwareRaw: Int,
    val soc: Int,
    val chargeOn: Boolean,
    val dischargeOn: Boolean,
    val cellCount: Int,
    val sensorCount: Int,
    val temperatures: List<Double>,
) {
    /** Версія програми плати: 0x80 → «8.0», 0x13 → «1.3». */
    val firmware: String get() = "${firmwareRaw shr 4}.${firmwareRaw and 0x0F}"

    /** Номери комірок (з 1), що зараз балансуються. */
    fun balancingCells(): List<Int> = (0 until cellCount).filter { balanceMask and (1L shl it) != 0L }.map { it + 1 }
}

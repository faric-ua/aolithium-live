package ua.faric.aolithium.core

import java.util.Locale
import kotlin.math.abs

/**
 * Історія показників: одна точка — один запис у файл (раз на [HistoryMath.RECORD_EVERY_MS]).
 * Формат рядка CSV: `час_мс,заряд,напруга,струм,залишок,темп_мін,темп_макс,к1;к2;к3;к4`.
 */
data class HistoryPoint(
    val t: Long,
    val soc: Int,
    val voltage: Double,
    val current: Double,
    val remainAh: Double,
    val tempMin: Double?,
    val tempMax: Double?,
    val cellsMv: List<Int>,
) {
    val power: Double get() = voltage * current
    val deltaMv: Int? get() = if (cellsMv.size < 2) null else cellsMv.max() - cellsMv.min()

    fun toCsv(): String = buildString {
        append(t).append(',').append(soc).append(',')
        append(fmt(voltage, 2)).append(',').append(fmt(current, 2)).append(',').append(fmt(remainAh, 2)).append(',')
        append(tempMin?.let { fmt(it, 1) } ?: "").append(',').append(tempMax?.let { fmt(it, 1) } ?: "").append(',')
        append(cellsMv.joinToString(";"))
    }

    companion object {
        private fun fmt(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)

        fun of(s: BatterySnapshot, t: Long): HistoryPoint = HistoryPoint(
            t = t, soc = s.soc, voltage = s.voltage, current = s.current, remainAh = s.remainAh,
            tempMin = s.temperatures.minOrNull(), tempMax = s.temperatures.maxOrNull(), cellsMv = s.cellsMv,
        )

        /** Зіпсований рядок (обірваний запис) — null, не падати. */
        fun parse(line: String): HistoryPoint? {
            val p = line.trim().split(',')
            if (p.size < 8) return null
            return try {
                HistoryPoint(
                    t = p[0].toLong(),
                    soc = p[1].toInt(),
                    voltage = p[2].toDouble(),
                    current = p[3].toDouble(),
                    remainAh = p[4].toDouble(),
                    tempMin = p[5].takeIf { it.isNotEmpty() }?.toDouble(),
                    tempMax = p[6].takeIf { it.isNotEmpty() }?.toDouble(),
                    cellsMv = p[7].split(';').filter { it.isNotEmpty() }.map { it.toInt() },
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

/** Підсумки за період — для картки «За період» на вкладці «Графіки». */
data class HistoryStats(
    val points: Int,
    val chargedWh: Double,
    val dischargedWh: Double,
    val chargedAh: Double,
    val dischargedAh: Double,
    val socMin: Int?,
    val socMax: Int?,
    val maxChargeA: Double,
    val maxDischargeA: Double,
    val tempMin: Double?,
    val tempMax: Double?,
    val deltaMaxMv: Int?,
    val coveredMs: Long,
)

object HistoryMath {
    const val RECORD_EVERY_MS = 10_000L

    /**
     * Довша перерва між точками — запису не було (застосунок закритий, фон вимкнений): лінію не з'єднуємо,
     * енергію не рахуємо. До v0.2.5 було 60 с (точки лише раз на 10 с з відкритого екрана). З записом у фоні
     * точки йдуть раз на ~5 хв, а вночі Android може будити рідше (до ~10 хв) — тому 12 хв, інакше фонові точки
     * на графіку були б окремими крапками, а «Заряджено / Віддано» — нулі.
     */
    const val GAP_MS = 12 * 60_000L

    /**
     * Звести точки до [buckets] шматків часу між [from] і [to]: середнє в кожному шматку.
     * Порожній шматок — null (на графіку розрив).
     */
    fun bucket(points: List<HistoryPoint>, from: Long, to: Long, buckets: Int, value: (HistoryPoint) -> Double?): List<Pair<Long, Double>?> {
        require(buckets > 0 && to > from)
        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        val span = (to - from).toDouble()
        for (p in points) {
            if (p.t < from || p.t > to) continue
            val v = value(p) ?: continue
            val i = (((p.t - from) / span) * buckets).toInt().coerceIn(0, buckets - 1)
            sums[i] += v
            counts[i]++
        }
        return (0 until buckets).map { i ->
            if (counts[i] == 0) null else (from + ((i + 0.5) * span / buckets).toLong()) to sums[i] / counts[i]
        }
    }

    fun stats(points: List<HistoryPoint>): HistoryStats {
        var inWh = 0.0
        var outWh = 0.0
        var inAh = 0.0
        var outAh = 0.0
        var covered = 0L
        val sorted = points.sortedBy { it.t }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > GAP_MS) continue
            covered += dt
            val h = dt / 3_600_000.0
            val w = (a.power + b.power) / 2
            val amp = (a.current + b.current) / 2
            if (w >= 0) inWh += w * h else outWh += -w * h
            if (amp >= 0) inAh += amp * h else outAh += -amp * h
        }
        val temps = sorted.flatMap { listOfNotNull(it.tempMin, it.tempMax) }
        return HistoryStats(
            points = sorted.size,
            chargedWh = inWh,
            dischargedWh = outWh,
            chargedAh = inAh,
            dischargedAh = outAh,
            socMin = sorted.minOfOrNull { it.soc },
            socMax = sorted.maxOfOrNull { it.soc },
            maxChargeA = sorted.maxOfOrNull { it.current }?.coerceAtLeast(0.0) ?: 0.0,
            maxDischargeA = sorted.minOfOrNull { it.current }?.let { abs(it.coerceAtMost(0.0)) } ?: 0.0,
            tempMin = temps.minOrNull(),
            tempMax = temps.maxOrNull(),
            deltaMaxMv = sorted.mapNotNull { it.deltaMv }.maxOrNull(),
            coveredMs = covered,
        )
    }
}

/** Здоров'я батареї й порада щодо зарядного — з того, що знає плата. */
object Health {
    /** Ємність з етикетки — з назви моделі: «AL12-100-4S P814» → 100 А·год. Не вийшло — null. */
    fun ratedAh(model: String?): Int? {
        if (model == null) return null
        val m = Regex("""\d{1,2}-(\d{2,4})-\d{1,2}S""", RegexOption.IGNORE_CASE).find(model) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 5..2000 }
    }

    /** Скільки відсотків від етикетки плата вважає повною ємністю (≈ «здоров'я»). */
    fun sohPercent(fullAh: Double, rated: Int?): Int? =
        if (rated == null || rated <= 0 || fullAh <= 0) null else ((fullAh / rated) * 100).toInt()

    /** Перезаряд комірки спрацьовував стільки разів, що варто перевірити зарядний пристрій. */
    const val OVERCHARGE_MANY = 50

    fun overchargeAdvice(count: Int): String? =
        if (count < OVERCHARGE_MANY) null
        else tr(
            "Плата $count разів зупиняла заряд через перезаряд комірки. Найчастіша причина — зарядний пристрій дає " +
                "забагато вольт (для 4 комірок LiFePO4 зазвичай радять 14,2–14,4 В на заряді й 13,5–13,6 В на підтримці), " +
                "або одна комірка доходить до межі раніше за інших (тоді різниця комірок наприкінці заряду велика). " +
                "Плата при цьому батарею захистила — це не поломка, але часті спрацювання старять комірки. " +
                "Перевір налаштування зарядного (інвертора) — це орієнтир, не діагноз.",
            "The BMS stopped charging $count times because of cell overcharge. The most common cause is that the charger gives " +
                "too many volts (for 4 LiFePO4 cells, 14.2–14.4 V for charging and 13.5–13.6 V for float are usually recommended), " +
                "or one cell reaches the limit before the others (then the cell difference at the end of charge is big). " +
                "The BMS protected the battery — this is not a fault, but frequent trips age the cells. " +
                "Check the charger (inverter) settings — this is a guide, not a diagnosis.",
        )
}

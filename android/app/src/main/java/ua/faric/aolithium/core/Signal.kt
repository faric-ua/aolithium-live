package ua.faric.aolithium.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Сила сигналу Bluetooth (dBm, чим ближче до нуля — тим ближче пристрій) людською мовою,
 * і як показувати її в списку без мерехтіння (faric 18.10 20:31: «пристрої бігають вверх-вниз дуже швидко»).
 *
 * Сигнал Bluetooth сам по собі скаче на ±5–10 dBm щосекунди, навіть коли все стоїть на місці.
 * Тому: (1) порядок у списку — за тим, коли пристрій з'явився, і більше не міняється;
 * (2) число згладжуємо і міняємо на екрані, лише коли воно зсунулось на [STEP] і більше.
 */
object Signal {
    /** Крок, менше якого число на екрані не міняється. */
    const val STEP = 3

    /** Слабше за це — підключення часто зривається (на A26 18.10 при −97 — код 133 щоразу). */
    const val TOO_FAR = -88

    /** Три слова (faric 19.10: «дуже слабкий» не влазило в рядок — лишаємо просто «слабкий»). */
    enum class Level {
        STRONG, MEDIUM, WEAK;

        /** T12: getter, не аргумент enum — щоб після перемикання мови було новою мовою. */
        val title: String
            get() = when (this) {
                STRONG -> tr("сильний", "strong")
                MEDIUM -> tr("середній", "medium")
                WEAK -> tr("слабкий", "weak")
            }
    }

    fun level(rssi: Int): Level = when {
        rssi >= -70 -> Level.STRONG
        rssi > TOO_FAR -> Level.MEDIUM
        else -> Level.WEAK
    }

    fun tooFar(rssi: Int): Boolean = rssi <= TOO_FAR

    /** Згладжування: нове значення важить третину. */
    fun smooth(previous: Int?, fresh: Int): Int =
        if (previous == null) fresh else ((previous * 2 + fresh) / 3.0).roundToInt()

    /** Що показати: старе число, поки нове не відійшло на [STEP] dBm. */
    fun shown(previousShown: Int?, smoothed: Int): Int =
        if (previousShown == null || abs(smoothed - previousShown) >= STEP) smoothed else previousShown

    /** «−97 dBm, слабкий» — коротко, щоб влазило в рядок. */
    fun describe(rssi: Int): String = "$rssi dBm, ${level(rssi).title}"

    /** T81: старше за це — «у ефірі не видно з …», а не сигнал. */
    const val FRESH_MS = 15 * 60_000L

    /**
     * T81 (faric 25.10: «я бачу список батарей, але силу сигналу не бачу»): рядок під батареєю в «Моїх батареях» —
     * останній сигнал з фонового слухання ефіру чи пошуку. [rssi]/[at] — null, якщо ще не бачили.
     */
    fun seen(rssi: Int?, at: Long?, now: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (rssi == null || at == null || at <= 0) return tr("сигнал: ще не бачив у ефірі", "signal: not seen on the air yet")
        val ago = now - at
        if (ago >= FRESH_MS) return tr("у ефірі не видно з ${Alerts.at(at, now, tz)}", "not on the air since ${Alerts.at(at, now, tz)}")
        val min = (ago / 60_000L).toInt()
        val whenText = if (min < 1) tr("щойно", "just now") else tr("$min хв тому", "$min min ago")
        return tr("сигнал ${describe(rssi)} · $whenText", "signal ${describe(rssi)} · $whenText")
    }
}

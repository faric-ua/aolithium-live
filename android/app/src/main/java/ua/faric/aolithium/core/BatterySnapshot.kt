package ua.faric.aolithium.core

import kotlin.math.abs

/**
 * Усе, що відомо про батарею в одну мить — однаково для будь-якої плати
 * (зараз JBD/Aolithium; пізніше JK BMS/Logic Power — той самий екран).
 */
data class BatterySnapshot(
    val voltage: Double,
    /** Плюс — заряджається, мінус — віддає. */
    val current: Double,
    val soc: Int,
    val remainAh: Double,
    val fullAh: Double,
    val cycles: Int,
    val cellsMv: List<Int>,
    val temperatures: List<Double>,
    val balancingCells: List<Int>,
    val protections: List<Protection>,
    val unknownProtectionBits: List<Int>,
    val chargeOn: Boolean,
    val dischargeOn: Boolean,
    val firmware: String?,
    val made: Ymd?,
    val model: String?,
    val errorCounts: List<Pair<String, Int>>?,
    val cellCount: Int,
) {
    val power: Double get() = voltage * current

    val flow: Flow get() = when {
        current > IDLE_A -> Flow.CHARGING
        current < -IDLE_A -> Flow.DISCHARGING
        else -> Flow.IDLE
    }

    /** Середня напруга комірки: з комірок, а без них — напруга батареї / кількість комірок. */
    val averageCellV: Double? get() = when {
        cellsMv.isNotEmpty() -> cellsMv.average() / 1000.0
        cellCount > 0 -> voltage / cellCount
        else -> null
    }

    val minCell: Int? get() = cellsMv.withIndex().minByOrNull { it.value }?.index
    val maxCell: Int? get() = cellsMv.withIndex().maxByOrNull { it.value }?.index

    /** Різниця між найвищою і найнижчою коміркою, мВ. */
    val deltaMv: Int? get() = if (cellsMv.size < 2) null else cellsMv.max() - cellsMv.min()

    val deltaVerdict: Verdict? get() = deltaMv?.let { Verdict.ofDelta(it) }

    /**
     * Скільки ще годин: розряд — до порожньої, заряд — до повної. Лише коли струм помітний.
     * Грубо: за нинішнім струмом, без поправок на температуру й старіння.
     */
    val hoursLeft: Double? get() = when (flow) {
        Flow.DISCHARGING -> remainAh / abs(current)
        Flow.CHARGING -> if (fullAh > remainAh) (fullAh - remainAh) / current else 0.0
        Flow.IDLE -> null
    }

    val hasProblem: Boolean get() = protections.isNotEmpty() || unknownProtectionBits.isNotEmpty()

    enum class Flow { CHARGING, DISCHARGING, IDLE }

    /** Оцінка різниці комірок — орієнтир, не діагноз. */
    enum class Verdict(val title: String) {
        GOOD("рівно"),
        WATCH("помірна різниця"),
        BAD("велика різниця");

        companion object {
            const val WATCH_FROM_MV = 30
            const val BAD_FROM_MV = 100

            fun ofDelta(mv: Int): Verdict = when {
                mv >= BAD_FROM_MV -> BAD
                mv >= WATCH_FROM_MV -> WATCH
                else -> GOOD
            }
        }
    }

    companion object {
        /** Порожній знімок — коли з батареєю ще ніколи не було зв'язку (екран показує прочерки, faric 18.10 21:48). */
        val EMPTY = BatterySnapshot(
            voltage = 0.0, current = 0.0, soc = 0, remainAh = 0.0, fullAh = 0.0, cycles = 0, cellsMv = emptyList(),
            temperatures = emptyList(), balancingCells = emptyList(), protections = emptyList(), unknownProtectionBits = emptyList(),
            chargeOn = false, dischargeOn = false, firmware = null, made = null, model = null, errorCounts = null, cellCount = 0,
        )

        /** Струм менше 0,05 А вважаємо «стоїть». */
        const val IDLE_A = 0.05

        /** Зібрати з відповідей JBD: «Основне» обов'язкове, решта — якщо прийшло. */
        fun fromJbd(
            basic: JbdBasic,
            cellsMv: List<Int>,
            model: String?,
            errorCounts: List<Pair<String, Int>>?,
        ): BatterySnapshot = BatterySnapshot(
            voltage = basic.voltage,
            current = basic.current,
            soc = basic.soc,
            remainAh = basic.remainAh,
            fullAh = basic.fullAh,
            cycles = basic.cycles,
            // Плата інколи шле більше напруг, ніж комірок (порожні) — лишаємо стільки, скільки комірок.
            cellsMv = if (basic.cellCount in 1..cellsMv.size) cellsMv.take(basic.cellCount) else cellsMv,
            temperatures = basic.temperatures,
            balancingCells = basic.balancingCells(),
            protections = Protection.fromMask(basic.protectionMask),
            unknownProtectionBits = Protection.unknownBits(basic.protectionMask),
            chargeOn = basic.chargeOn,
            dischargeOn = basic.dischargeOn,
            firmware = basic.firmware,
            made = basic.made,
            model = model?.takeIf { it.isNotBlank() },
            errorCounts = errorCounts,
            cellCount = basic.cellCount,
        )
    }
}

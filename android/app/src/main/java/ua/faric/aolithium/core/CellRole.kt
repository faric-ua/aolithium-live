package ua.faric.aolithium.core

/**
 * T23 (faric 19.10 22:06: «кольори комірок як у Logic Power»). Активний балансир JK переливає енергію з однієї комірки в
 * іншу — і каже, коли працює. Яка комірка віддає, а яка бере: `BatterySnapshot.fromJk` кладе в `balancingCells`
 * [найвища, найнижча] (з 1), саме так переливає активний балансир. JBD (Aolithium) напряму не каже — там [Role.NONE]
 * і стара мітка «вирівнюється».
 */
object CellRole {
    enum class Role { NONE, GIVES, TAKES }

    /** [cellNo] — номер комірки з 1. */
    fun of(s: BatterySnapshot, cellNo: Int): Role {
        val jk = s.jk ?: return Role.NONE
        if (!jk.balancing || s.balancingCells.size != 2) return Role.NONE
        return when (cellNo) {
            s.balancingCells[0] -> Role.GIVES
            s.balancingCells[1] -> Role.TAKES
            else -> Role.NONE
        }
    }
}

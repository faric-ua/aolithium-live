package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** T23: яка комірка JK віддає, яка бере (з найвищої в найнижчу), а в JBD — без напряму. */
class CellRoleTest {
    @Test fun jk_balancer_gives_from_highest_to_lowest() {
        val asm = JkAssembler()
        var device: JkDevice? = null
        var cells: JkCells? = null
        for (chunk in (JkDemo.device() + JkDemo.cells()).toList().chunked(20)) {
            for (f in asm.feed(chunk.toByteArray())) when (f.type) {
                Jk.TYPE_DEVICE -> device = JkParser.device(f.bytes)
                Jk.TYPE_CELLS -> cells = JkParser.cells(f.bytes, device?.swMajor ?: 0)
            }
        }
        val s = BatterySnapshot.fromJk(cells!!, device)
        // демо: 3412 / 3398 / 3371 / 3405 — найвища №1, найнижча №3
        assertEquals(CellRole.Role.GIVES, CellRole.of(s, 1))
        assertEquals(CellRole.Role.TAKES, CellRole.of(s, 3))
        assertEquals(CellRole.Role.NONE, CellRole.of(s, 2))
        val idle = s.copy(jk = s.jk!!.copy(balancerAction = 0), balancingCells = emptyList())
        assertEquals(CellRole.Role.NONE, CellRole.of(idle, 1))
    }

    @Test fun jbd_has_no_direction() {
        val s = BatterySnapshot.fromJk(JkParser.cells(JkDemo.cells())!!, null).copy(jk = null)
        assertEquals(CellRole.Role.NONE, CellRole.of(s, 1))
    }
}

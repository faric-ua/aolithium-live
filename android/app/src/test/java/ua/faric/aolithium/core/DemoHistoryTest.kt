package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T29: у демо «Logic Power» — своя історія (305 А·год, розкид як у живих комірках JK), Aolithium — як було. */
class DemoHistoryTest {
    private val now = 1_760_000_000_000L

    @Test fun jk_scene_gets_logic_power_history() {
        assertEquals(DemoHistory.LOGIC_POWER, DemoHistory.profileFor("JK"))
        for (scene in listOf("IDLE", "CHARGING", "DISCHARGING", "COLD")) assertEquals(DemoHistory.AOLITHIUM, DemoHistory.profileFor(scene))
    }

    @Test fun jk_history_matches_live_jk_frames() {
        val pts = DemoHistory.week(now, DemoHistory.LOGIC_POWER)
        assertTrue("залишок до 305 А·год", pts.maxOf { it.remainAh } > 200 && pts.maxOf { it.remainAh } <= 305.0)
        val liveSpread = JkDemo.CELLS_MV.max() - JkDemo.CELLS_MV.min()
        assertEquals("наприкінці заряду розкид — як у живих комірках JK", liveSpread, pts.maxOf { it.deltaMv ?: 0 })
        // найнижча й найвища — ті самі комірки, що в живих кадрах (№3 і №1)
        val top = pts.first { (it.deltaMv ?: 0) == liveSpread }
        assertEquals(JkDemo.CELLS_MV.indexOf(JkDemo.CELLS_MV.min()), top.cellsMv.indexOf(top.cellsMv.min()))
        assertEquals(JkDemo.CELLS_MV.indexOf(JkDemo.CELLS_MV.max()), top.cellsMv.indexOf(top.cellsMv.max()))
    }

    @Test fun aolithium_history_unchanged() {
        val pts = DemoHistory.week(now)
        assertTrue(pts.maxOf { it.remainAh } <= 92.34)
        assertEquals(28, pts.maxOf { it.deltaMv ?: 0 })
        assertEquals(listOf(3297, 3325, 3311, 3312), DemoHistory.cells(DemoHistory.AOLITHIUM, 3311, 28))
    }

    @Test fun both_have_charge_and_discharge_and_four_cells() {
        for (p in listOf(DemoHistory.AOLITHIUM, DemoHistory.LOGIC_POWER)) {
            val pts = DemoHistory.week(now, p)
            assertTrue(pts.any { it.current > 0 } && pts.any { it.current < 0 })
            assertTrue(pts.all { it.cellsMv.size == 4 && it.soc in 12..100 })
        }
    }
}

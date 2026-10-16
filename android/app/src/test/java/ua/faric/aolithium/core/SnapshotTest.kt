package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    private fun snap(
        current: Double = 0.0,
        remain: Double = 50.0,
        full: Double = 100.0,
        cells: List<Int> = listOf(3300, 3310, 3305, 3302),
        cellCount: Int = 4,
        protections: List<Protection> = emptyList(),
    ) = BatterySnapshot(
        voltage = 13.2, current = current, soc = 50, remainAh = remain, fullAh = full, cycles = 1,
        cellsMv = cells, temperatures = listOf(20.0), balancingCells = emptyList(), protections = protections,
        unknownProtectionBits = emptyList(), chargeOn = true, dischargeOn = true, firmware = "1.0", made = null,
        model = null, errorCounts = null, cellCount = cellCount,
    )

    @Test
    fun flowAndHours() {
        assertEquals(BatterySnapshot.Flow.IDLE, snap(current = 0.03).flow)
        assertNull(snap(current = 0.03).hoursLeft)
        val dis = snap(current = -10.0)
        assertEquals(BatterySnapshot.Flow.DISCHARGING, dis.flow)
        assertEquals(5.0, dis.hoursLeft!!, 1e-9)
        assertEquals(-132.0, dis.power, 1e-9)
        val chg = snap(current = 25.0, remain = 50.0)
        assertEquals(BatterySnapshot.Flow.CHARGING, chg.flow)
        assertEquals(2.0, chg.hoursLeft!!, 1e-9)
        assertEquals(0.0, snap(current = 5.0, remain = 100.0).hoursLeft!!, 1e-9)
    }

    @Test
    fun cellsDeltaAndExtremes() {
        val s = snap()
        assertEquals(10, s.deltaMv)
        assertEquals(0, s.minCell)
        assertEquals(1, s.maxCell)
        assertEquals(BatterySnapshot.Verdict.GOOD, s.deltaVerdict)
        assertEquals(BatterySnapshot.Verdict.WATCH, BatterySnapshot.Verdict.ofDelta(30))
        assertEquals(BatterySnapshot.Verdict.BAD, BatterySnapshot.Verdict.ofDelta(100))
        assertNull(snap(cells = listOf(3300)).deltaMv)
    }

    @Test
    fun averageWithoutCellsUsesPackVoltage() {
        assertEquals(3.3, snap(cells = emptyList()).averageCellV!!, 1e-9)
        assertNull(snap(cells = emptyList(), cellCount = 0).averageCellV)
    }

    @Test
    fun extraCellValuesAreTrimmed() {
        val basic = JbdParser.basic(ByteArray(23).also { it[21] = 4 })!!
        val s = BatterySnapshot.fromJbd(basic, listOf(3300, 3301, 3302, 3303, 0, 0), "  ", null)
        assertEquals(4, s.cellsMv.size)
        assertNull(s.model)
    }

    @Test
    fun problemFlag() {
        assertFalse(snap().hasProblem)
        assertTrue(snap(protections = listOf(Protection.CELL_OVER)).hasProblem)
    }

    @Test
    fun formatting() {
        assertEquals("13,31", Fmt.num(13.305001, 2))
        assertEquals("0,00", Fmt.num(-0.001, 2))
        assertEquals("-2,87 А", Fmt.amps(-2.87))
        assertEquals("-132 Вт", Fmt.watts(-132.0))
        assertEquals("12,5 Вт", Fmt.watts(12.46))
        assertEquals("3,328 В", Fmt.cellVolts(3328))
        assertEquals("45 хв", Fmt.duration(0.75))
        assertEquals("3 год", Fmt.duration(3.0))
        assertEquals("3 год 20 хв", Fmt.duration(3.3333))
        assertEquals("понад 99 год", Fmt.duration(150.0))
        assertEquals("щойно", Fmt.ago(1200))
        assertEquals("12 с тому", Fmt.ago(12_000))
        assertEquals("3 хв тому", Fmt.ago(200_000))
    }

    @Test
    fun plurals() {
        assertEquals("1 цикл", Fmt.cycles(1))
        assertEquals("2 цикли", Fmt.cycles(2))
        assertEquals("5 циклів", Fmt.cycles(5))
        assertEquals("11 циклів", Fmt.cycles(11))
        assertEquals("123 цикли", Fmt.cycles(123))
        assertEquals("21 цикл", Fmt.cycles(21))
    }

    @Test
    fun recognizesBoards() {
        // Вигадана адреса з тим самим початком, що в Aolithium (справжню адресу faric у репо не пишемо).
        assertEquals(BmsMatch.Kind.JBD, BmsMatch.kind("a4:c1:37:00:00:01", "AL12-100-4S"))
        assertEquals(BmsMatch.Kind.JBD, BmsMatch.kind("11:22:33:44:55:66", "JBD-SP04S034"))
        assertEquals(BmsMatch.Kind.JBD, BmsMatch.kind("11:22:33:44:55:66", "SJ-120-ab"))
        assertEquals(BmsMatch.Kind.JBD, BmsMatch.kind("11:22:33:44:55:66", null, listOf(Jbd.SERVICE_UUID)))
        assertEquals(BmsMatch.Kind.OTHER, BmsMatch.kind("11:22:33:44:55:66", "Galaxy Buds"))
        assertEquals(
            BmsMatch.Kind.JK,
            BmsMatch.kind("C8:47:80:00:00:01", "JK-B2A24S", listOf(BmsMatch.JK_SERVICE.uppercase()), listOf(0x0B65)),
        )
        assertEquals("A4:C1:37:··:··:01", BmsMatch.shortAddress("A4:C1:37:00:00:01"))
        assertTrue(BmsMatch.glob("N-?????BL*", "N-12345BL-x"))
        assertFalse(BmsMatch.glob("N-?????BL*", "N-1234BL"))
    }
}

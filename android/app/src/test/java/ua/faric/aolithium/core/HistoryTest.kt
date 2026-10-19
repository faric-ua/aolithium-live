package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun p(t: Long, a: Double, v: Double = 13.0, soc: Int = 50, cells: List<Int> = listOf(3300, 3310, 3305, 3302)) =
        HistoryPoint(t, soc, v, a, 50.0, 12.3, 18.5, cells)

    @Test
    fun csvRoundTrip() {
        val x = p(1_700_000_000_000, -2.87, 13.31, 93)
        val line = x.toCsv()
        assertEquals("1700000000000,93,13.31,-2.87,50.00,12.3,18.5,3300;3310;3305;3302", line)
        assertEquals(x, HistoryPoint.parse(line))
        assertNull(HistoryPoint.parse("1700000000000,93,13.3")) // обірваний рядок
        assertNull(HistoryPoint.parse("abc,93,13.31,-2.87,50,,,"))
        val noTemp = HistoryPoint.parse("1,93,13.31,0.00,50.00,,,")!!
        assertNull(noTemp.tempMin)
        assertEquals(emptyList<Int>(), noTemp.cellsMv)
    }

    @Test
    fun energyAndGaps() {
        // Годину по 10 с: розряд 10 А при 13 В = 130 Вт → 130 Вт·год віддано, 10 А·год.
        val pts = (0..360).map { p(it * 10_000L, -10.0) }
        val s = HistoryMath.stats(pts)
        assertEquals(130.0, s.dischargedWh, 0.01)
        assertEquals(10.0, s.dischargedAh, 0.01)
        assertEquals(0.0, s.chargedWh, 1e-9)
        assertEquals(10.0, s.maxDischargeA, 1e-9)
        assertEquals(3_600_000L, s.coveredMs)
        // Розрив 15 хв (застосунок закритий, фон вимкнений) — не рахується.
        // До v0.2.5 межа була 1 хв; з фоновим записом раз на ~5 хв її підняли до 12 хв.
        val gap = listOf(p(0, 5.0), p(15 * 60_000L, 5.0))
        assertEquals(0.0, HistoryMath.stats(gap).chargedWh, 1e-9)
        // Фонові точки раз на 5 хв — рахуються: година розряду 10 А = 10 А·год.
        val bg = (0..12).map { p(it * 5 * 60_000L, -10.0) }
        assertEquals(10.0, HistoryMath.stats(bg).dischargedAh, 0.01)
        assertEquals(10, s.deltaMaxMv)
    }

    @Test
    fun bucketsAverageAndGaps() {
        val pts = listOf(p(0, 1.0), p(1000, 3.0), p(9000, 10.0))
        val b = HistoryMath.bucket(pts, 0, 10_000, 5) { it.current }
        assertEquals(5, b.size)
        assertEquals(2.0, b[0]!!.second, 1e-9)
        assertNull(b[1])
        assertEquals(10.0, b[4]!!.second, 1e-9)
    }

    @Test
    fun zones() {
        assertEquals(Zones.Tone.RED, Zones.of(5).tone)
        assertEquals(Zones.Tone.ORANGE, Zones.of(15).tone)
        assertEquals(Zones.Tone.GREEN, Zones.of(55).tone)
        assertEquals(Zones.Tone.ORANGE, Zones.of(100).tone)
        assertEquals(Zones.Tone.RED, Zones.of(-3).tone)
        for (s in 0..100) Zones.of(s) // кожен відсоток має зону
    }

    @Test
    fun health() {
        assertEquals(100, Health.ratedAh("AL12-100-4S P814"))
        assertEquals(280, Health.ratedAh("XX24-280-8S"))
        assertNull(Health.ratedAh("DEMO-JBD-4S-100A"))
        assertNull(Health.ratedAh(null))
        assertEquals(92, Health.sohPercent(92.34, 100))
        assertNull(Health.sohPercent(92.34, null))
        assertNull(Health.overchargeAdvice(3))
        assertTrue(Health.overchargeAdvice(2016)!!.contains("2016"))
    }

    @Test
    fun glossaryIsComplete() {
        val ids = Glossary.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (e in Glossary.ALL) {
            assertTrue(e.id, e.what.length > 20)
            assertTrue(e.id, e.normal.isNotBlank() && e.worry.isNotBlank())
        }
        for (name in JbdParser.ERROR_COUNT_NAMES) assertTrue(name, Glossary.get(Glossary.forCounter(name)) != null)
        for (id in listOf("soc", "zones", "voltage", "current", "power", "cells", "delta", "temp", "signal", "history", "soh", "energy")) {
            assertTrue(id, Glossary.get(id) != null)
        }
    }
}

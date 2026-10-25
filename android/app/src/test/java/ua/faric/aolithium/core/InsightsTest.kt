package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private fun p(t: Long, a: Double, soc: Int = 50, v: Double = 13.2, cells: List<Int> = listOf(3300, 3301, 3302, 3300)) =
        HistoryPoint(t, soc, v, a, 50.0, 18.0, 19.0, cells)

    @Test
    fun steadyCurrentIgnoresSmallWobble() {
        val s = SteadyCurrent()
        assertEquals(-4.0, s.add(0, -4.0), 1e-9)
        // ±5 % туди-сюди — показане число не міняється
        s.add(2_000, -4.2)
        s.add(4_000, -3.9)
        assertEquals(-4.0, s.shown!!, 1e-9)
        // стабільно 6 А протягом 2 хв — середнє відійшло > 10 % → перерахунок
        var t = 6_000L
        while (t < 130_000L) { s.add(t, -6.0); t += 2_000 }
        assertEquals(-6.0, s.shown!!, 0.2)
        // заряд замість розряду — одразу
        s.reset()
        s.add(0, -3.0)
        assertTrue(s.add(1_000, 20.0) > 0)
    }

    @Test
    fun timeLeftThresholds() {
        // 58,9 з 92 А·год, віддає 4,1 А
        val d = TimeLeft.discharge(58.9, 92.0, -4.1)!!
        assertEquals(14.37, d.totalH, 0.01)
        val to20 = d.toThreshold.first { it.first == 20 }.second!!
        assertEquals((58.9 - 18.4) / 4.1, to20, 1e-6)
        // нижче 20 % — порогу 20 вже нема
        val low = TimeLeft.discharge(15.0, 100.0, -5.0)!!
        assertNull(low.toThreshold.first { it.first == 20 }.second)
        assertNotNull(low.toThreshold.first { it.first == 10 }.second)
        assertNull(TimeLeft.discharge(50.0, 100.0, 2.0))
        val c = TimeLeft.charge(58.9, 92.0, 6.2)!!
        assertEquals(33.1 / 6.2, c.toFullH, 1e-6)
        assertEquals(6.74, c.percentPerHour, 0.01)
        assertNull(TimeLeft.charge(58.9, 92.0, -1.0))
    }

    @Test
    fun cellTrend() {
        assertEquals(listOf(-12, 16, 2, -6), CellTrend.fromAverage(listOf(3442, 3470, 3456, 3448)))
        val samples = listOf(
            0L to listOf(3440, 3463, 3451, 3444),
            30_000L to listOf(3441, 3466, 3453, 3446),
            60_000L to listOf(3442, 3470, 3456, 3448),
        )
        assertEquals(listOf(2, 7, 5, 4), CellTrend.perMinute(samples))
        assertNull(CellTrend.perMinute(samples.take(1)))
        // ±1 мВ — шум плати (точність 1 мВ), показуємо «без змін».
        assertEquals(0, CellTrend.shown(1))
        assertEquals(0, CellTrend.shown(-1))
        assertEquals(-2, CellTrend.shown(-2))
    }

    @Test
    fun marksAreTrueAndEqualCellsAllMarked() {
        // T33 (відео faric 20.10 06:21): 3,326 / 3,329 / 3,328 / 3,329 — «найвища» і на №2, і на №4.
        assertEquals(setOf(1, 3) to setOf(0), CellTrend.marks(listOf(3326, 3329, 3328, 3329)))
        // Відео 19.10 13:57: №3 і №4 обидві 3,334 — обидві «найнижча», нема між чим стрибати.
        assertEquals(setOf(1) to setOf(2, 3), CellTrend.marks(listOf(3336, 3337, 3334, 3334)))
        assertEquals(setOf(1) to setOf(3), CellTrend.marks(listOf(3336, 3337, 3334, 3333)))
        // Скрін 19.10 22:06 (T24): 3,332 / 3,331 / 3,330 / 3,329 — найвища №1, найнижча №4.
        assertEquals(setOf(0) to setOf(3), CellTrend.marks(listOf(3332, 3331, 3330, 3329)))
        // Усі однакові або різниця 1 мВ (точність плати) — без міток; одна комірка — без міток.
        assertEquals(emptySet<Int>() to emptySet<Int>(), CellTrend.marks(listOf(3335, 3335, 3335, 3335)))
        assertEquals(emptySet<Int>() to emptySet<Int>(), CellTrend.marks(listOf(3336, 3335, 3335, 3336)))
        assertEquals(emptySet<Int>() to emptySet<Int>(), CellTrend.marks(listOf(3335)))
    }

    @Test
    fun outagesFromHistory() {
        val pts = ArrayList<HistoryPoint>()
        var t = 0L
        // 10 хв заряд, 60 хв розряд 10 А (130 Вт), 20 с «нуль» посередині — не розрив, 10 хв заряд
        repeat(60) { pts += p(t, 5.0, 80); t += 10_000 }
        val start = t
        repeat(360) { i -> pts += p(t, if (i == 180) 0.0 else -10.0, 80 - i / 30); t += 10_000 }
        val end = t - 10_000
        repeat(60) { pts += p(t, 5.0, 68); t += 10_000 }
        val o = Outages.find(pts)
        assertEquals(1, o.size)
        assertEquals(start, o[0].start)
        assertEquals(end, o[0].end)
        assertFalse(o[0].ongoing)
        assertEquals(80, o[0].socFrom)
        assertEquals(10.0, o[0].ah, 0.2)
        assertEquals(130.0, o[0].avgW, 3.0)
        // коротке (2 хв) — не рахується; триває досі — ongoing
        val short = (0 until 12).map { p(it * 10_000L, -8.0) }
        assertTrue(Outages.find(short).isEmpty())
        val now = (0 until 60).map { p(it * 10_000L, -3.0) }
        assertTrue(Outages.find(now).single().ongoing)
    }

    @Test
    fun outagesIgnoreSmallDrawOfFullBattery() {
        // T85: 32 хв по 1,2 А при 100 % — не відключення; те саме при 60 % — відключення (поріг 0,5 А), з порогом 2 А — ні.
        val full = (0 until 200).map { p(it * 10_000L, -1.2, 100) }
        assertTrue(Outages.find(full).isEmpty())
        val mid = (0 until 200).map { p(it * 10_000L, -1.2, 60) }
        assertEquals(1, Outages.find(mid).size)
        assertTrue(Outages.find(mid, userA = 2.0).isEmpty())
        assertEquals(2.0, Outages.startA(0.5, 98), 1e-9)
        assertEquals(0.5, Outages.startA(0.5, 97), 1e-9)
        assertEquals(3.0, Outages.startA(3.0, 100), 1e-9)
    }

    @Test
    fun dailyMinAndLaggard() {
        val day = DailyMin.DAY_MS
        val pts = listOf(p(1_000, 0.0, 70), p(5_000, 0.0, 40), p(day + 1_000, 0.0, 33))
        assertEquals(listOf(40, 33), DailyMin.of(pts, 0).map { it.minSoc })

        // три кінці заряду; №3 найнижча двічі, №2 — раз
        val top = listOf(
            p(0, 10.0, 99, cells = listOf(3480, 3485, 3470, 3482)),
            p(10_000, 10.0, 100, cells = listOf(3516, 3519, 3503, 3518)),
            p(3 * 3_600_000L, 5.0, 100, cells = listOf(3506, 3505, 3512, 3508)),
            p(9 * 3_600_000L, 5.0, 96, cells = listOf(3509, 3509, 3503, 3508)),
            p(9 * 3_600_000L + 1, -5.0, 96, cells = listOf(3200, 3100, 3200, 3200)), // розряд — не рахується
        )
        val r = Laggard.find(top)!!
        assertEquals(3, r.episodes)
        assertEquals(listOf(0, 1, 2, 0), r.lowestCount)
        assertEquals(2, r.leader)
        assertEquals(listOf(16, 7, 6), r.deltas)
        assertNull(Laggard.find(listOf(p(0, -3.0))))
    }

    @Test
    fun charger() {
        val c = ChargerCheck.of(listOf(p(0, 10.0, v = 14.05), p(1, 2.0, v = 14.16), p(2, 0.0, v = 14.3)))!!
        assertEquals(14.16, c.maxV, 1e-9)
        assertEquals(ChargerCheck.Verdict.OK, c.verdict)
        assertEquals(ChargerCheck.Verdict.HIGH, ChargerCheck.of(listOf(p(0, 1.0, v = 14.8)))!!.verdict)
        assertNull(ChargerCheck.of(listOf(p(0, -1.0))))
    }
}

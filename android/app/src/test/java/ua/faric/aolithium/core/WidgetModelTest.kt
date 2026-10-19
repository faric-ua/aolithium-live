package ua.faric.aolithium.core

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModelTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")
    // 19.10.2026 19:40:00 за Києвом
    private val at = 1_792_428_000_000L
    private val min = 60_000L

    private fun snap(current: Double, soc: Int) = BatterySnapshot(
        voltage = 13.2, current = current, soc = soc, remainAh = 92.3 * soc / 100.0, fullAh = 92.3, cycles = 123,
        cellsMv = listOf(3300, 3301, 3300, 3302), temperatures = listOf(15.0), balancingCells = emptyList(),
        protections = emptyList(), unknownProtectionBits = emptyList(), chargeOn = true, dischargeOn = true,
        firmware = "2.3", made = null, model = "AL12-100-4S", errorCounts = null, cellCount = 4,
    )

    @Test
    fun discharging() {
        val m = WidgetModel.of("Aolithium", snap(-24.0, 64), at, at + 3 * min, kyiv)
        assertEquals(WidgetModel.Tone.DISCHARGE, m.tone)
        assertEquals("64 %", m.percent)
        assertEquals(0.64f, m.ring, 1e-6f)
        assertEquals("59,1 А·год", m.ringNote)
        assertEquals("317 Вт", m.power)
        assertEquals(WidgetModel.Arrow.UP, m.arrow)
        assertEquals("віддає — світла нема", m.status)
        // (59,07 − 18,46) А·год / 24 А = 1,69 год
        assertEquals("вистачить ≈ 1 год 42 хв до 20 %", m.line1)
        assertEquals("записано 19:40", m.line2)
        assertEquals("≈ 1 год 42 хв · 19:40", m.small)
    }

    @Test
    fun dischargingBelow20SaysChargeNow() {
        val m = WidgetModel.of("A", snap(-10.0, 15), at, at, kyiv)
        assertEquals("до кінця ≈ 1 год 23 хв — час заряджати", m.line1)
    }

    @Test
    fun charging() {
        val m = WidgetModel.of("A", snap(46.0, 42), at, at, kyiv)
        assertEquals(WidgetModel.Tone.CHARGE, m.tone)
        assertEquals(WidgetModel.Arrow.DOWN, m.arrow)
        assertEquals("заряджається", m.status)
        assertEquals("до повної ≈ 1 год 10 хв · +50 % за год", m.line1)
    }

    @Test
    fun idleFull() {
        val m = WidgetModel.of("A", snap(0.0, 100), at, at, kyiv)
        assertEquals(WidgetModel.Tone.IDLE, m.tone)
        assertEquals("стоїть", m.power)
        assertEquals("повна — ні заряду, ні розряду", m.status)
        assertEquals("повна · 19:40", m.small)
    }

    @Test
    fun staleAfter15Minutes() {
        assertEquals(WidgetModel.Tone.DISCHARGE, WidgetModel.of("A", snap(-24.0, 85), at, at + 15 * min, kyiv).tone)
        val m = WidgetModel.of("A", snap(-24.0, 85), at, at + 125 * min, kyiv)
        assertEquals(WidgetModel.Tone.STALE, m.tone)
        assertEquals("85 %", m.percent)
        assertEquals("о 19:40", m.ringNote)
        assertEquals("нема даних", m.power)
        assertEquals("2 год 5 хв — телефон далеко?", m.status)
        assertEquals("показано останнє, що було о 19:40", m.line1)
        assertEquals("нема даних з 19:40", m.small)
    }

    @Test
    fun neverConnected() {
        val m = WidgetModel.of("A", null, null, at, kyiv)
        assertEquals(WidgetModel.Tone.EMPTY, m.tone)
        assertEquals("—", m.percent)
        assertEquals(0f, m.ring, 0f)
    }
}

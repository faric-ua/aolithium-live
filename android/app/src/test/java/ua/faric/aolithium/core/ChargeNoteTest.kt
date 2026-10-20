package ua.faric.aolithium.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T39: смужка «Заряджається» — коли з'являється, що пише, коли зникає. */
class ChargeNoteTest {
    @After fun uk() {
        Lang.current = Lang.Code.UK
    }

    private fun snap(current: Double, soc: Int = 64, chargeOn: Boolean = true, fullAh: Double = 92.3) = BatterySnapshot(
        voltage = 13.4, current = current, soc = soc, remainAh = fullAh * soc / 100.0, fullAh = fullAh, cycles = 123,
        cellsMv = listOf(3350, 3351, 3350, 3352), temperatures = listOf(15.0), balancingCells = emptyList(),
        protections = emptyList(), unknownProtectionBits = emptyList(), chargeOn = chargeOn, dischargeOn = true,
        firmware = null, made = null, model = "AL12-100-4S", errorCounts = null, cellCount = 4,
    )

    @Test fun shows_while_charging_with_time_to_full() {
        // 64 % з 92,3 А·год → бракує 33,2 А·год; при 25 А — ≈ 1 год 20 хв.
        val n = ChargeNote.of(snap(25.0), showing = false)
        assertNotNull(n)
        assertEquals("Заряджається · 64 %", n!!.title)
        assertEquals(64, n.percent)
        assertTrue(n.text, n.text.contains("до повної ≈ 1 год 20 хв"))
        assertTrue(n.text, n.text.contains("Вт"))
    }

    @Test fun not_shown_when_idle_or_discharging() {
        assertNull(ChargeNote.of(snap(0.0), showing = false))
        assertNull(ChargeNote.of(snap(-12.0), showing = false))
        assertNull(ChargeNote.of(snap(-12.0), showing = true))
    }

    @Test fun small_current_does_not_start_but_keeps_shown_bar() {
        // Балансир наприкінці заряду: 0,3 А — нову смужку не показуємо, а вже показану не прибираємо (не блимає).
        assertNull(ChargeNote.of(snap(0.3, soc = 98), showing = false))
        assertNotNull(ChargeNote.of(snap(0.3, soc = 98), showing = true))
        // Струм зник зовсім — прибираємо.
        assertNull(ChargeNote.of(snap(0.04, soc = 98), showing = true))
    }

    @Test fun gone_when_full_or_bms_stopped_charge() {
        assertNull(ChargeNote.of(snap(5.0, soc = 100), showing = true))
        assertNull(ChargeNote.of(snap(5.0, chargeOn = false), showing = true))
    }

    @Test fun almost_full_and_unknown_capacity() {
        val near = ChargeNote.of(snap(25.0, soc = 99, fullAh = 92.3).copy(remainAh = 92.3), showing = true)
        assertTrue(near!!.text, near.text.contains("майже повна"))
        val unknown = ChargeNote.of(snap(10.0, fullAh = 0.0), showing = false)
        assertTrue(unknown!!.text, unknown.text.contains("рахую"))
    }

    @Test fun english() {
        Lang.current = Lang.Code.EN
        val n = ChargeNote.of(snap(25.0), showing = false)!!
        assertEquals("Charging · 64 %", n.title)
        assertTrue(n.text, n.text.contains("to full ≈ 1 h 20 min"))
        assertFalse(Regex("[А-Яа-яІіЇїЄєҐґ]").containsMatchIn(n.title + n.text))
    }
}

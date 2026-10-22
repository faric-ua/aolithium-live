package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** T69: без зв'язку спідометр міняє «немає зв'язку» ↔ «останні дані» кожні 3 с; підпис з часом. */
class StaleShowTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kyiv")

    @Test fun blinks_every_three_seconds() {
        assertFalse("0–3 с — немає зв'язку", StaleShow.showOld(0))
        assertFalse(StaleShow.showOld(2_999))
        assertTrue("3–6 с — останні дані", StaleShow.showOld(3_000))
        assertTrue(StaleShow.showOld(5_999))
        assertFalse("знову немає", StaleShow.showOld(6_000))
        assertEquals(1_000, StaleShow.msToSwitch(5_000))
        assertEquals(3_000, StaleShow.msToSwitch(6_000))
    }

    @Test fun label_with_time_or_date() {
        val at = 1792635960000L // 22.10.2026 05:26 за Києвом
        assertEquals("дані від 05:26", StaleShow.label(at, at + 10 * 60_000L, kyiv))
        assertEquals("учора — з датою", "дані від 22.10 05:26", StaleShow.label(at, at + 24 * 3_600_000L, kyiv))
    }
}

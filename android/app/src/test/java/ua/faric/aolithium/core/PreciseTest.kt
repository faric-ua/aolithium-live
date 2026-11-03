package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T91: «Точний запис» — коли зупиняється сам і як часто пише. */
class PreciseTest {
    @Test fun screen_on_never_stops_by_itself() {
        assertNull(Precise.stopReason(now = 10 * 3_600_000L, screenOffSince = null))
        assertNull(Precise.stopsAt(null))
    }

    @Test fun locked_screen_records_five_minutes_then_stops() {
        val off = 1_000_000L
        assertNull(Precise.stopReason(off + 4 * 60_000L, off))
        assertNull(Precise.stopReason(off + Precise.LOCKED_MAX_MS - 1, off))
        assertEquals(Precise.Stop.LOCKED, Precise.stopReason(off + Precise.LOCKED_MAX_MS, off))
        assertEquals(off + 5 * 60_000L, Precise.stopsAt(off))
    }

    @Test fun records_every_reading_only_when_on() {
        assertEquals(1_500L, Precise.recordEvery(true))
        assertEquals(HistoryMath.RECORD_EVERY_MS, Precise.recordEvery(false))
        // плата читається раз на 2 с — точний запис мусить брати кожне читання
        assertEquals(true, Precise.EVERY_MS < 2_000L)
    }

    @Test fun stop_reasons_have_text_in_both_languages() {
        try {
            for (s in Precise.Stop.entries) {
                Lang.current = Lang.Code.UK
                val uk = s.text
                Lang.current = Lang.Code.EN
                val en = s.text
                assertEquals(false, uk.isBlank() || en.isBlank() || uk == en)
            }
        } finally {
            Lang.current = Lang.Code.UK
        }
    }
}

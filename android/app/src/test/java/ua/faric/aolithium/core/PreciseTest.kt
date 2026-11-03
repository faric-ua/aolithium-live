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
        assertEquals(Precise.EVERY_MS, Precise.recordEvery(true))
        assertEquals(HistoryMath.RECORD_EVERY_MS, Precise.recordEvery(false))
        // T93: плата читається ~щосекунди — точний запис мусить брати кожне читання
        assertEquals(true, Precise.EVERY_MS < 1_000L)
        assertEquals(true, Precise.EVERY_MS > Precise.POLL_MS) // запис не частіше, ніж справді приходять читання
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

    @Test fun t93_reads_about_every_second_only_when_on() {
        assertEquals(600L, Precise.pollMs(true, 2_000L))
        assertEquals(2_000L, Precise.pollMs(false, 2_000L))
    }

    @Test fun t93_charts_live_only_for_short_ranges() {
        assertEquals(30_000L, Precise.chartReloadMs(false, 15 * 60_000L))
        assertEquals(1_000L, Precise.chartReloadMs(true, 15 * 60_000L))
        assertEquals(1_000L, Precise.chartReloadMs(true, 3_600_000L))
        assertEquals(5_000L, Precise.chartReloadMs(true, 6 * 3_600_000L))
        assertEquals(30_000L, Precise.chartReloadMs(true, 86_400_000L))
    }

    @Test fun t93_fifteen_minutes_has_a_point_per_second() {
        assertEquals(900, Precise.chartBuckets(15 * 60_000L))
        assertEquals(720, Precise.chartBuckets(3_600_000L))
        assertEquals(240, Precise.chartBuckets(6 * 3_600_000L))
        assertEquals(240, Precise.chartBuckets(7 * 86_400_000L))
    }
}

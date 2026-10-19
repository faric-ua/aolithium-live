package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.faric.aolithium.core.LinkLine.State

/** T25: рядок «Зв'язок» — завжди два короткі рядки, без переносів і без пропадань. */
class LinkLineTest {
    private val now = 1_000_000_000L

    @Test fun every_state_gives_two_non_empty_single_lines() {
        for (st in State.entries) for (live in listOf(0L, now - 5_000)) for (saved in listOf(0L, now - 480_000)) {
            val m = LinkLine.build(st, attempt = 2, ofAttempts = 3, message = "Батарея далеко. Підійди ближче.",
                rssi = -90, liveAt = live, liveStamp = "19.10 22:11", savedAt = saved, savedStamp = "19.10 22:04", now = now)
            for (s in listOf(m.pill, m.detail, m.second)) {
                assertTrue("$st: порожньо", s.isNotBlank())
                assertFalse("$st: перенос рядка в «$s»", s.contains('\n'))
            }
        }
    }

    @Test fun lost_shows_repeat_count_and_signal_in_one_line() {
        val m = LinkLine.build(State.LOST, attempt = 2, ofAttempts = 3, rssi = -90, savedAt = now - 480_000,
            savedStamp = "19.10 22:04", now = now)
        assertEquals("Зв'язок втрачено", m.pill)
        assertEquals("повтор 2 з 3 · -90 dBm, слабкий", m.detail)
        assertEquals("нижче — дані від 19.10 22:04 (8 хв тому)", m.second)
        assertTrue(m.retry)
        assertEquals(LinkLine.Tone.WARN, m.tone)
    }

    @Test fun connecting_first_try_and_repeat() {
        assertEquals("зазвичай 2–5 с", LinkLine.build(State.CONNECTING, now = now).detail)
        assertEquals("повтор 1 з 3", LinkLine.build(State.CONNECTING, attempt = 1, ofAttempts = 3, now = now).detail)
        assertFalse(LinkLine.build(State.CONNECTING, now = now).retry)
        assertFalse(LinkLine.build(State.READING, now = now).retry)
    }

    @Test fun live_says_updated_ago() {
        val m = LinkLine.build(State.LIVE, liveAt = now - 2_000, now = now)
        assertEquals("оновлено щойно", m.second)
        assertEquals("дані наживо", m.detail)
        assertFalse(m.retry)
        assertEquals("демо, без батареї", LinkLine.build(State.LIVE, liveAt = now, now = now, demo = true).detail)
    }

    @Test fun lost_after_live_points_at_this_session_data() {
        val m = LinkLine.build(State.LOST, liveAt = now - 30_000, liveStamp = "19.10 22:11",
            savedAt = now - 480_000, savedStamp = "19.10 22:04", now = now)
        assertEquals("нижче — дані від 19.10 22:11 (30 с тому)", m.second)
    }

    @Test fun never_connected() {
        assertEquals("даних ще нема — замість чисел прочерки", LinkLine.build(State.FAILED, now = now).second)
    }

    @Test fun failed_takes_first_sentence_only() {
        val m = LinkLine.build(State.FAILED, message = "Bluetooth вимкнено. Увімкни його й натисни «Підключити ще раз».", now = now)
        assertEquals("Bluetooth вимкнено", m.detail)
        assertEquals(LinkLine.Tone.BAD, m.tone)
        assertEquals("торкни — що робити", LinkLine.build(State.NO_ANSWER, now = now).detail)
    }

    @Test fun first_sentence_keeps_numbers_with_dots() {
        assertEquals("Сигнал -90 dBm, слабкий", LinkLine.firstSentence("Сигнал -90 dBm, слабкий"))
        assertEquals("Версія 2.3 не відповідає", LinkLine.firstSentence("Версія 2.3 не відповідає. Далі."))
    }
}

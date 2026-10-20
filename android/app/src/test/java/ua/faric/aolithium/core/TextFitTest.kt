package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T30: значення плитки — в один рядок; спершу менший шрифт, потім «…». */
class TextFitTest {
    @Test fun fits_already_keeps_size() {
        assertEquals(17f, TextFit.size(17f, needPx = 100f, availPx = 120f, minPx = 12f), 0.001f)
        assertEquals(17f, TextFit.size(17f, needPx = 120f, availPx = 120f, minPx = 12f), 0.001f)
    }

    @Test fun no_width_yet_keeps_size() {
        assertEquals(17f, TextFit.size(17f, needPx = 300f, availPx = 0f, minPx = 12f), 0.001f)
    }

    @Test fun slightly_too_long_shrinks_until_it_fits() {
        // «8 відключень» 17 sp на A26 зі шрифтом 1.3 — трохи ширше за плитку.
        val s = TextFit.size(17f, needPx = 130f, availPx = 120f, minPx = 12f)
        assertTrue("менше за 17: $s", s < 17f)
        assertTrue("влазить: ${130f * s / 17f}", 130f * s / 17f <= 120f)
        assertTrue("не дрібніше за мінімум", s >= 12f)
    }

    @Test fun far_too_long_stops_at_minimum_rest_is_ellipsis() {
        assertEquals(12f, TextFit.size(17f, needPx = 400f, availPx = 120f, minPx = 12f), 0.001f)
    }

    @Test fun never_grows() {
        assertEquals(10f, TextFit.size(10f, needPx = 130f, availPx = 120f, minPx = 12f), 0.001f)
    }

    @Test fun row_shares_the_smallest_size() {
        assertEquals(15f, TextFit.shared(0f, 15f), 0.001f)
        assertEquals(14f, TextFit.shared(15f, 14f), 0.001f)
        assertEquals(14f, TextFit.shared(14f, 16f), 0.001f)
    }
}

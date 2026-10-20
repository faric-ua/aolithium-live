package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T51: «0.4.10 · збірка 26» — в один рядок; не влазить — назва над числом. */
class VersionLineTest {
    @Test fun version_has_no_place_to_break() {
        val t = VersionLine.text("0.4.10", 26, "збірка")
        assertFalse("звичайних пробілів нема: «$t»", t.contains(' '))
        assertEquals("0.4.10 · збірка 26", t.replace(' ', ' '))
    }

    @Test fun english_too() {
        assertEquals("0.4.10 · build 26", VersionLine.text("0.4.10", 26, "build").replace(' ', ' '))
    }

    @Test fun fits_in_row_stays_in_row() {
        // A26, шрифт 1.0: «Встановлено» ~90 px, версія ~150 px, рядок ~330 px.
        assertFalse(VersionLine.stacked(labelPx = 90f, valuePx = 150f, rowPx = 330f, gapPx = 12f))
    }

    @Test fun too_wide_label_goes_above() {
        // Великий шрифт / вузький екран: разом ширші за рядок — назва над числом.
        assertTrue(VersionLine.stacked(labelPx = 160f, valuePx = 230f, rowPx = 330f, gapPx = 12f))
    }

    @Test fun no_width_yet_stays_in_row() {
        assertFalse(VersionLine.stacked(labelPx = 160f, valuePx = 230f, rowPx = 0f, gapPx = 12f))
    }
}

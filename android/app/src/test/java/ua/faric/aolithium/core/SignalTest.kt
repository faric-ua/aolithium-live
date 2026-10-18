package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {
    @Test
    fun levels() {
        assertEquals(Signal.Level.STRONG, Signal.level(-66)) // так було на відео Aolithium 16.10
        assertEquals(Signal.Level.MEDIUM, Signal.level(-78))
        assertEquals(Signal.Level.WEAK, Signal.level(-85))
        assertEquals(Signal.Level.VERY_WEAK, Signal.level(-97)) // так було 18.10, коли не підключалось
        assertTrue(Signal.tooFar(-97))
        assertFalse(Signal.tooFar(-80))
        assertEquals("сигнал -97 dBm, дуже слабкий", Signal.describe(-97))
    }

    @Test
    fun shownNumberDoesNotFlicker() {
        // Справжні значення з відео faric 18.10: один пристрій −83, −88, −78, −85, −96 за кілька секунд.
        var smooth: Int? = null
        var shown: Int? = null
        val history = ArrayList<Int>()
        for (r in listOf(-83, -84, -82, -83, -85, -83, -84)) {
            smooth = Signal.smooth(smooth, r)
            shown = Signal.shown(shown, smooth)
            history += shown
        }
        assertEquals(listOf(-83), history.distinct()) // дрібне тремтіння — число стоїть
        shown = Signal.shown(shown, Signal.smooth(smooth, -60))
        assertTrue(shown!! > -83) // справжня зміна — число міняється
    }

    @Test
    fun smoothing() {
        assertEquals(-90, Signal.smooth(null, -90))
        assertEquals(-87, Signal.smooth(-90, -81))
    }
}

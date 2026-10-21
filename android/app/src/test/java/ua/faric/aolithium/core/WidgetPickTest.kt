package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T22: кожен віджет — своя батарея; «автоматично» — як до v0.4.16. Адреси вигадані (не справжня батарея faric). */
class WidgetPickTest {
    private val aol = WidgetPick.Option("A4:C1:37:00:00:01", lastSeen = 1_000, bgOn = false)
    private val jk = WidgetPick.Option("C8:47:80:00:00:02", lastSeen = 2_000, bgOn = false)

    @Test
    fun chosenBatteryWins() {
        assertEquals(aol.address, WidgetPick.choose(aol.address, listOf(aol, jk)))
        assertEquals(jk.address, WidgetPick.choose(jk.address, listOf(aol, jk.copy(bgOn = false))))
        // Навіть коли для іншої ввімкнено фон.
        assertEquals(aol.address, WidgetPick.choose(aol.address, listOf(aol, jk.copy(bgOn = true))))
    }

    @Test
    fun chosenAddressIgnoresCase() {
        assertEquals(aol.address, WidgetPick.choose(aol.address.lowercase(), listOf(aol, jk)))
    }

    @Test
    fun autoPrefersBackgroundThenLatest() {
        // Як було до v0.4.16: з «Записом у фоні», інакше — найсвіжіший зв'язок.
        assertEquals(aol.address, WidgetPick.choose(WidgetPick.AUTO, listOf(aol.copy(bgOn = true), jk)))
        assertEquals(jk.address, WidgetPick.choose(WidgetPick.AUTO, listOf(aol, jk)))
        assertEquals(jk.address, WidgetPick.choose(null, listOf(aol, jk)))
    }

    @Test
    fun forgottenBatteryFallsBackToAuto() {
        // Вибрану батарею прибрали з «Моїх батарей» — віджет не порожній, а «автоматично».
        assertEquals(jk.address, WidgetPick.choose("A4:C1:37:00:00:99", listOf(aol, jk)))
    }

    @Test
    fun noBatteriesNoAddress() {
        assertNull(WidgetPick.choose(aol.address, emptyList()))
        assertNull(WidgetPick.choose(null, emptyList()))
    }

    @Test
    fun askOnlyWhenThereIsAChoice() {
        assertFalse(WidgetPick.needsAsk(0))
        assertFalse(WidgetPick.needsAsk(1))
        assertTrue(WidgetPick.needsAsk(2))
    }
}

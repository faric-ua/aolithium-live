package ua.faric.aolithium.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12: довідник, зони, сигнал, зарядний і порада — англійською без кирилиці; після перемикання — знову українською. */
class GlossaryEnTest {
    private val cyr = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private fun assertEnglish(where: String, text: String) {
        assertTrue("$where: порожньо", text.isNotBlank())
        assertFalse("$where: кирилиця в «$text»", cyr.containsMatchIn(text))
    }

    @After
    fun back() {
        Lang.current = Lang.Code.UK
    }

    @Test
    fun everyGlossaryEntryAndGroupIsEnglish() {
        Lang.current = Lang.Code.EN
        for (g in Glossary.Group.entries) assertEnglish("group ${g.name}", g.title)
        val all = Glossary.ALL
        assertTrue(all.size >= 40)
        for (e in all) {
            assertEnglish("${e.id}.title", e.title)
            e.aolithium?.let { assertEnglish("${e.id}.aolithium", it) }
            assertEnglish("${e.id}.what", e.what)
            assertEnglish("${e.id}.normal", e.normal)
            assertEnglish("${e.id}.worry", e.worry)
            assertTrue(e.id, e.what.length > 20)
        }
        for ((g, entries) in Glossary.byGroup()) {
            assertEnglish("byGroup ${g.name}", g.title)
            for (e in entries) assertEnglish("byGroup ${e.id}", e.title)
        }
    }

    @Test
    fun glossarySwitchesBackAndKeepsIds() {
        Lang.current = Lang.Code.UK
        val ukIds = Glossary.ALL.map { it.id }
        assertEquals("Заряд, %", Glossary.get("soc")!!.title)
        assertEquals("Заряд", Glossary.Group.CHARGE.title)
        Lang.current = Lang.Code.EN
        assertEquals(ukIds, Glossary.ALL.map { it.id })
        assertEquals("Charge, %", Glossary.get("soc")!!.title)
        assertEquals("Charge", Glossary.Group.CHARGE.title)
        Lang.current = Lang.Code.UK
        assertEquals("Заряд, %", Glossary.get("soc")!!.title)
        // Лічильники — за назвою з протоколу, мова не важить.
        for (name in JbdParser.ERROR_COUNT_NAMES) assertNotNull(name, Glossary.get(Glossary.forCounter(name)))
        assertEquals("cnt_cell_over", Glossary.forCounter("Перезаряд комірки"))
    }

    @Test
    fun zonesSignalChargerAdviceAreEnglish() {
        Lang.current = Lang.Code.EN
        for (z in Zones.ALL) {
            assertEnglish("zone ${z.from}.title", z.title)
            assertEnglish("zone ${z.from}.advice", z.advice)
        }
        assertEquals("Working zone", Zones.of(55).title)
        assertEquals("-97 dBm, weak", Signal.describe(-97))
        for (l in Signal.Level.entries) assertEnglish("signal ${l.name}", l.title)
        for (v in ChargerCheck.Verdict.entries) assertEnglish("charger ${v.name}", v.title)
        val advice = Health.overchargeAdvice(2016)!!
        assertEnglish("overchargeAdvice", advice)
        assertTrue(advice.contains("2016"))

        Lang.current = Lang.Code.UK
        assertEquals("Робоча зона", Zones.of(55).title)
        assertEquals("-97 dBm, слабкий", Signal.describe(-97))
        assertEquals("в нормі", ChargerCheck.Verdict.OK.title)
    }
}

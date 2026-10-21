package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T50: «Подзвонити» лише при серйозному; номери чисті; застосунок лише відкриває набір. */
class EmergencyTest {
    @Test fun numbers_are_cleaned_and_checked() {
        assertEquals("+380501234567", Emergency.clean(" +38 (050) 123-45-67 "))
        assertEquals("101", Emergency.clean("101"))
        assertTrue(Emergency.valid("101"))
        assertTrue(Emergency.valid("+380501234567"))
        assertFalse(Emergency.valid("12"))
        assertFalse(Emergency.valid("абв"))
        assertEquals("tel:+380501234567", Emergency.dialUri(Emergency.Contact("Іван", "+38 050 123 45 67")))
    }

    @Test fun plan_lists_rescue_first_then_own_number() {
        val p = Emergency.Plan(rescue = true, contact = Emergency.Contact("Сусід Іван", "+380501234567"))
        assertEquals(listOf("101", "+380501234567"), p.contacts.map { Emergency.clean(it.number) })
        assertEquals(listOf("Подзвонити 101", "Подзвонити Сусід Іван"), p.contacts.map { Emergency.buttonLabel(it) })
        assertTrue(Emergency.Plan(rescue = false).contacts.isEmpty())
        // Зіпсований номер не стає кнопкою.
        assertEquals(1, Emergency.Plan(true, Emergency.Contact("x", "12")).contacts.size)
    }

    @Test fun contact_survives_the_phone_memory() {
        val c = Emergency.Contact("Сусід\tІван", "+38 050 123 45 67")
        val back = Emergency.decodeContact(Emergency.encodeContact(c))
        assertEquals("Сусід Іван", back?.name)
        assertEquals("+380501234567", back?.number)
        assertNull(Emergency.decodeContact(""))
        assertNull(Emergency.decodeContact(Emergency.encodeContact(null)))
    }

    @Test fun telegram_text_has_the_numbers_and_the_advice() {
        val t = Emergency.telegramText(Emergency.Plan(true, Emergency.Contact("Сусід Іван", "+380501234567")))
        assertTrue(t.contains("101"))
        assertTrue(t.contains("Сусід Іван: +380501234567"))
        assertTrue(t.contains("не підходь"))
        assertFalse(Emergency.telegramText(Emergency.Plan(rescue = false)).contains("101"))
    }

    private fun snap(prot: List<Protection> = emptyList(), temps: List<Double> = listOf(20.0)) = BatterySnapshot(
        voltage = 13.3, current = 0.0, soc = 80, remainAh = 70.0, fullAh = 92.0, cycles = 1, cellsMv = listOf(3325, 3325, 3325, 3325),
        temperatures = temps, balancingCells = emptyList(), protections = prot, unknownProtectionBits = emptyList(),
        chargeOn = true, dischargeOn = true, firmware = null, made = null, model = null, errorCounts = null, cellCount = 4,
    )

    @Test fun overheating_and_short_circuit_are_urgent_but_cold_and_overdischarge_are_not() {
        assertTrue(Alerts.protectionEvent(snap(listOf(Protection.CHARGE_HOT)), setOf(Protection.CHARGE_HOT.name)).urgent)
        assertTrue(Alerts.protectionEvent(snap(listOf(Protection.SHORT_CIRCUIT)), setOf(Protection.SHORT_CIRCUIT.name)).urgent)
        assertFalse(Alerts.protectionEvent(snap(listOf(Protection.CELL_UNDER)), setOf(Protection.CELL_UNDER.name)).urgent)
        assertFalse(Alerts.protectionEvent(snap(listOf(Protection.CHARGE_COLD)), setOf(Protection.CHARGE_COLD.name)).urgent)
    }

    @Test fun hot_is_urgent_and_carries_the_numbers_to_telegram() {
        val hot = Alerts.onSnapshot(Alerts.State(), snap(temps = listOf(55.0)), 1_000_000L).events.first { it.kind == Alerts.Kind.TEMP }
        assertTrue(hot.urgent)
        val cold = Alerts.onSnapshot(Alerts.State(), snap(temps = listOf(-5.0)), 1_000_000L).events.first { it.kind == Alerts.Kind.TEMP }
        assertFalse(cold.urgent)
        val m = Guard.onSnapshot(Guard.State(), snap(temps = listOf(55.0)), 1_000_000L, "Aolithium").messages.first { it.kind == Guard.Kind.TEMP }
        assertTrue(m.urgent)
        assertTrue(m.render(Emergency.Plan()).contains("101"))
        // Без плану (старий виклик) — як було, без номерів.
        assertFalse(m.render().contains("101"))
    }

    @Test fun urgent_test_is_clearly_marked() {
        val m = Guard.urgentTest("Aolithium")
        assertTrue(m.urgent)
        assertTrue(m.title.startsWith("ПЕРЕВІРКА"))
        assertTrue(m.render(Emergency.Plan()).contains("101"))
    }
}

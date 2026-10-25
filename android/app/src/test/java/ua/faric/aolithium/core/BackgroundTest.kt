package ua.faric.aolithium.core

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")
    // 19.10.2026 04:35:10 за Києвом (UTC+3) = 01:35:10 UTC
    private val at = 1_792_373_710_000L

    @Test
    fun decideSkipsWhenScreenHoldsBatteryFirst() {
        assertEquals(Background.Skip.SCREEN_OPEN, Background.decide(screenHoldsIt = true, permissionOk = false, bluetoothOn = false))
        assertEquals(Background.Skip.NO_PERMISSION, Background.decide(false, permissionOk = false, bluetoothOn = true))
        assertEquals(Background.Skip.BLUETOOTH_OFF, Background.decide(false, permissionOk = true, bluetoothOn = false))
        assertNull(Background.decide(false, permissionOk = true, bluetoothOn = true))
    }

    @Test
    fun flowWords() {
        assertEquals("віддає 4,10 А", Background.flow(-4.1))
        assertEquals("бере 8,50 А", Background.flow(8.5))
        assertEquals("стоїть", Background.flow(0.1))
        assertEquals("стоїть", Background.flow(-0.19))
    }

    @Test
    fun notificationAndStatusLines() {
        assertEquals("04:35", Background.hhmm(at, kyiv))
        val ok = Background.Result(at, ok = true, soc = 64, current = -4.1)
        assertEquals("Aolithium: 64 % · віддає 4,10 А · 04:35", Background.line("Aolithium", ok, kyiv))
        assertEquals("04:35 — 64 %, віддає 4,10 А", Background.status(ok, kyiv))
        val fail = Background.Result(at, ok = false)
        assertTrue(Background.line("A", fail, kyiv).contains("нема зв'язку"))
        assertEquals("04:35 — Bluetooth вимкнено", Background.status(Background.Result(at, false, note = Background.Skip.BLUETOOTH_OFF.text), kyiv))
        assertEquals("ще не було", Background.status(null))
        assertEquals("A: чекаю першого запису", Background.line("A", null))
        // T21: у сповіщенні час — у верхньому рядку, не в тексті.
        assertEquals("Aolithium: 64 % · віддає 4,10 А", Background.line("Aolithium", ok, kyiv, withTime = false))
        assertEquals("A: нема зв'язку — батарея далеко чи зайнята іншим телефоном", Background.line("A", fail, kyiv, withTime = false))
    }

    @Test
    fun nextTickIsFiveMinutesButNeverInThePast() {
        assertEquals(at + 5 * 60_000L, Background.nextAt(at, at + 10_000L))
        // Тік запізнився (Android спав 20 хв) — наступний не одразу, а через хвилину.
        assertEquals(at + 20 * 60_000L + 60_000L, Background.nextAt(at, at + 20 * 60_000L))
    }

    @Test
    fun retrySoonAfterFailureButNotForever() {
        // T55: не дістали — ще раз через 2 хв, двічі поспіль; далі — знову раз на 5 хв (телефон справді далеко).
        assertEquals(false, Background.retrySoon(0))
        assertEquals(true, Background.retrySoon(1))
        assertEquals(true, Background.retrySoon(2))
        assertEquals(false, Background.retrySoon(3))
        assertEquals(at + 2 * 60_000L, Background.nextAt(at, at + 50_000L, retrySoon = true))
        assertEquals(at + 5 * 60_000L, Background.nextAt(at, at + 50_000L, retrySoon = false))
        // Читання тривало 75 с — повтор не раніше ніж за хвилину від зараз.
        assertEquals(at + 75_000L + 60_000L, Background.nextAt(at, at + 75_000L, retrySoon = true))
    }

    @Test
    fun csvForSpreadsheets() {
        val p = HistoryPoint(at, 64, 13.12, -4.1, 59.2, 12.3, 14.0, listOf(3280, 3285, 3270, 3279))
        assertEquals("2026-10-19 04:35:10,$at,64,13.12,-4.10,-53.8,59.20,12.3,14.0,15,3280;3285;3270;3279", HistoryCsv.row(p, kyiv))
        assertEquals(11, HistoryCsv.HEADER.split(',').size)
        assertEquals(11, HistoryCsv.row(p, kyiv).split(',').size)
        val noTemp = p.copy(tempMin = null, tempMax = null, cellsMv = emptyList())
        assertEquals(11, HistoryCsv.row(noTemp, kyiv).split(',').size)
        assertEquals("aolithium-AL12-100-4S-P814-2026-10-19.csv", HistoryCsv.fileName("AL12-100-4S P814", at, kyiv))
        assertEquals("aolithium-battery-2026-10-19.csv", HistoryCsv.fileName("//", at, kyiv))
    }

    /** T40: розблокування — читаємо, лише якщо давніше хвилини й нічого не читаємо зараз. */
    @Test
    fun read_on_unlock_only_when_data_older_than_a_minute() {
        val t = 1_792_428_000_000L
        assertTrue(Background.readOnUnlock(lastTickAt = t - 5 * 60_000L, now = t, busy = false))
        assertTrue(Background.readOnUnlock(lastTickAt = 0L, now = t, busy = false))
        assertFalse(Background.readOnUnlock(lastTickAt = t - 30_000L, now = t, busy = false))
        assertFalse(Background.readOnUnlock(lastTickAt = t - 5 * 60_000L, now = t, busy = true))
    }

    // ---------- T81: причини й «ловити момент» ----------

    @Test
    fun reasonsAreConcrete() {
        assertEquals("NO_REPLY", Background.why(seen = true, rssi = -70, connected = true))
        assertEquals("NOT_SEEN", Background.why(seen = false, rssi = null, connected = false))
        assertEquals("WEAK:-94", Background.why(seen = true, rssi = -94, connected = false))
        assertEquals("NO_CONNECT:-75", Background.why(seen = true, rssi = -75, connected = false))
        assertEquals("батарею видно, але сигнал слабкий (-94 dBm) — далеко або стіна", Background.Skip.localize("WEAK:-94"))
        assertTrue(Background.Skip.localize("NOT_SEEN").startsWith("батареї не видно в ефірі"))
        assertTrue(Background.Skip.localize("NO_CONNECT:-75").contains("(-75 dBm)"))
        // старий запис і незнайомий текст — як було
        assertEquals(Background.Skip.NO_ANSWER.text, Background.Skip.localize("NO_ANSWER"))
        assertEquals("щось своє", Background.Skip.localize("щось своє"))
        assertTrue(Background.Skip.isNoLink("WEAK:-94"))
        assertTrue(Background.Skip.isNoLink("NO_ANSWER"))
        assertFalse(Background.Skip.isNoLink("SCREEN_OPEN"))
        assertFalse(Background.Skip.isNoLink("LOCATION_OFF"))
    }

    @Test
    fun locationOffIsAReason() {
        assertEquals(Background.Skip.LOCATION_OFF, Background.decide(false, permissionOk = true, bluetoothOn = true, locationOn = false))
        assertNull(Background.decide(false, permissionOk = true, bluetoothOn = true, locationOn = true))
    }

    @Test
    fun catchOnlyWhenDataIsOldAndSignalGood() {
        val min = 60_000L
        // даних нема 47 хв, сигнал −80 — читати
        assertTrue(Background.shouldCatch(at, -80, at - 47 * min, 0L, busy = false))
        // ще ні разу не читали — теж
        assertTrue(Background.shouldCatch(at, -80, null, 0L, busy = false))
        // дані свіжі (4 хв) — розклад сам прочитає
        assertFalse(Background.shouldCatch(at, -80, at - 4 * min, 0L, busy = false))
        // сигнал надто слабкий
        assertFalse(Background.shouldCatch(at, -95, at - 47 * min, 0L, busy = false))
        assertTrue(Background.shouldCatch(at, -90, at - 47 * min, 0L, busy = false))
        // щойно пробували — почекати
        assertFalse(Background.shouldCatch(at, -80, at - 47 * min, at - min, busy = false))
        // уже читаємо
        assertFalse(Background.shouldCatch(at, -80, at - 47 * min, 0L, busy = true))
    }

    @Test
    fun sightingLogIsThrottled() {
        val min = 60_000L
        assertTrue(Background.logSighting(at, 0L, -80, null))
        assertFalse(Background.logSighting(at, at - min, -81, -80))
        assertTrue(Background.logSighting(at, at - 6 * min, -81, -80))
        // рівень змінився (середній → слабкий) — одразу
        assertTrue(Background.logSighting(at, at - min, -92, -80))
    }

    @Test
    fun signalLineForMyBatteries() {
        val min = 60_000L
        assertEquals("сигнал: ще не бачив у ефірі", Signal.seen(null, null, at, kyiv))
        assertEquals("сигнал -78 dBm, середній · щойно", Signal.seen(-78, at - 20_000L, at, kyiv))
        assertEquals("сигнал -65 dBm, сильний · 3 хв тому", Signal.seen(-65, at - 3 * min, at, kyiv))
        assertEquals("у ефірі не видно з 04:15", Signal.seen(-78, at - 20 * min, at, kyiv))
    }
}

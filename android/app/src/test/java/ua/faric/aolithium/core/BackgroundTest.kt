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
}

package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** T64: дані для перевірки на відстані — що тримаємо, що затираємо, що пишемо у файл. */
class DiagTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")

    private fun jkDeviceWithSecrets(): ByteArray {
        val f = JkDemo.device()
        fun put(at: Int, s: String) = s.toByteArray().forEachIndexed { i, b -> f[at + i] = b }
        put(62, "1234")        // пароль пристрою
        put(86, "3092572134")  // серійний номер
        put(97, "0000")        // пароль
        put(102, "Input User") // дані користувача
        put(118, "123457")     // пароль налаштувань
        return f
    }

    @Test fun jk_passwords_and_serial_are_zeroed_but_model_and_date_stay() {
        val raw = Diag.Raw(1, Diag.JK, Jk.TYPE_DEVICE, jkDeviceWithSecrets())
        val r = Diag.redact(raw).bytes
        assertTrue((62 until 78).all { r[it] == 0.toByte() })
        assertTrue((86 until r.size).all { r[it] == 0.toByte() })
        // Модель, версії й назва лишились — за ними Claude перевіряє розбір.
        assertEquals("JK_B2A8S20P", JkParser.device(r)?.model)
        assertEquals("Logic Power 305", JkParser.device(r)?.name)
        // Оригінал не зіпсовано (його ще читає зв'язок).
        assertEquals('1'.code.toByte(), raw.bytes[62])
    }

    @Test fun report_never_contains_the_passwords() {
        val text = Diag.report(input(frames = listOf(Diag.Raw(1, Diag.JK, Jk.TYPE_DEVICE, jkDeviceWithSecrets()))), kyiv)
        // «1234» у шістнадцятковому вигляді — «31 32 33 34».
        assertFalse(text.contains("31 32 33 34"))
        assertFalse(text.contains("33 30 39 32 35 37"))
    }

    @Test fun other_frames_are_untouched() {
        val cells = Diag.Raw(1, Diag.JK, Jk.TYPE_CELLS, JkDemo.cells())
        assertTrue(Diag.redact(cells).bytes.contentEquals(cells.bytes))
        val jbd = Diag.Raw(1, Diag.JBD, Jbd.CMD_BASIC, byteArrayOf(1, 2, 3))
        assertEquals(jbd, Diag.redact(jbd))
    }

    @Test fun keeps_two_freshest_per_type_and_a_limit_overall() {
        var list = emptyList<Diag.Raw>()
        for (t in 1..10L) list = Diag.keep(list, Diag.Raw(t, Diag.JK, Jk.TYPE_CELLS, byteArrayOf(t.toByte())))
        list = Diag.keep(list, Diag.Raw(11, Diag.JK, Jk.TYPE_DEVICE, byteArrayOf()))
        val cells = list.filter { it.type == Jk.TYPE_CELLS }
        assertEquals(listOf(9L, 10L), cells.map { it.at })
        assertEquals(1, list.count { it.type == Jk.TYPE_DEVICE })
        for (t in 20..60L) list = Diag.keep(list, Diag.Raw(t, Diag.JBD, (t % 30).toInt(), byteArrayOf()))
        assertTrue(list.size <= Diag.MAX_FRAMES)
    }

    @Test fun self_checks_catch_a_wrong_layout() {
        val good = snap(13.31, listOf(3328, 3327, 3329, 3326))
        assertTrue(Diag.checks(good).first().startsWith("OK"))
        // Комірки розібрано не з тих місць — сума далеко від напруги.
        val bad = snap(13.31, listOf(3328, 3327, 3329, 3326, 3330, 3331, 3329, 3328))
        assertTrue(Diag.checks(bad).first().startsWith("!!"))
        assertTrue(Diag.checks(null).isEmpty())
    }

    @Test fun report_has_all_parts_and_hex_rows() {
        val text = Diag.report(input(
            frames = listOf(Diag.Raw(1_792_620_300_000, Diag.JK, Jk.TYPE_CELLS, JkDemo.cells())),
            log = listOf("10-22 01:05:00.000  JK…01: підключено"),
        ), kyiv)
        for (part in listOf("## Що порахував застосунок", "## Самоперевірки", "## Відповіді плати (1)", "## Журнал зв'язку", "000: ", "016: ")) {
            assertTrue(part, text.contains(part))
        }
        assertTrue(text.contains("JK кадр 0x02 · 300 байт"))
        assertTrue(text.contains("Напруга 13.31 В"))
    }

    @Test fun file_name_is_safe_and_dated() {
        assertEquals("aolithium-diag-20261022-0105.txt", Diag.fileName(1_792_620_300_000, kyiv))
    }

    private fun snap(v: Double, cells: List<Int>) = BatterySnapshot(
        voltage = v, current = 0.0, soc = 93, remainAh = 86.31, fullAh = 92.34, cycles = 123, cellsMv = cells,
        temperatures = listOf(12.3), balancingCells = emptyList(), protections = emptyList(), unknownProtectionBits = emptyList(),
        chargeOn = true, dischargeOn = true, firmware = null, made = null, model = null, errorCounts = null, cellCount = cells.size,
    )

    private fun input(frames: List<Diag.Raw> = emptyList(), log: List<String> = emptyList()) = Diag.Input(
        now = 1_792_620_300_000, app = "0.4.18 · збірка 34", android = "15 (API 35)", battery = "Logic Power", board = "JK BMS",
        snapshot = snap(13.31, listOf(3328, 3327, 3329, 3326)), snapshotAt = 1_792_620_290_000, frames = frames, log = log,
        requestedBy = "Aolithium_room", freshRead = true,
    )

    @Test fun send_as_soon_as_the_background_has_read() {
        // T66: файл 22.10 02:47 прийшов через ~50 с, хоча фон дочитав за 4 с
        assertFalse("ще нема свіжих кадрів", Diag.readyToSend(4_000, freshFrames = false, stillReading = true))
        assertFalse("кадри є, але фон ще читає", Diag.readyToSend(4_000, freshFrames = true, stillReading = true))
        assertTrue("кадри є і фон дочитав — одразу", Diag.readyToSend(6_000, freshFrames = true, stillReading = false))
        assertTrue("батарея далеко — не довше за 45 с", Diag.readyToSend(Diag.WAIT_MS, freshFrames = false, stillReading = true))
    }
}

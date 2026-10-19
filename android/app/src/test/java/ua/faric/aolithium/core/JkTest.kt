package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JK BMS (v0.4, T5). Справжніх кадрів з батареї в мами ще нема — тож перевіряємо на кадрах, зібраних за розкладкою
 * з aiobmsble / esphome-jk-bms (`JkDemo`), і окремо — сами місця байтів (щоб помилка в демо не сховала помилку розбору).
 * Справжня перевірка — біля батареї (відкладена перевірка «JK у мами»).
 */
class JkTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test fun read_commands_are_20_bytes_with_sum() {
        assertEquals("AA 55 90 EB 97 00 00 00 00 00 00 00 00 00 00 00 00 00 00 11", hex(Jk.readCommand(Jk.CMD_DEVICE_INFO)))
        assertEquals("AA 55 90 EB 96 00 00 00 00 00 00 00 00 00 00 00 00 00 00 10", hex(Jk.readCommand(Jk.CMD_CELL_INFO)))
    }

    @Test fun app_never_builds_a_write_command() {
        for (cmd in 0..0xFF) {
            if (cmd in Jk.READ_COMMANDS) continue
            try {
                Jk.readCommand(cmd)
                fail("команда 0x%02X мала бути заборонена".format(cmd))
            } catch (e: IllegalArgumentException) {
                // так і треба: усе, крім 0x96 / 0x97, у JK — запис
            }
        }
    }

    @Test fun assembler_joins_20_byte_chunks_skips_at_and_ready() {
        val frame = JkDemo.cells()
        val ready = Jk.CMD_HEAD + byteArrayOf(0xC8.toByte(), 1, 1) + ByteArray(12) + byteArrayOf(0x44)
        val stream = Jk.AT_MSG + frame + ready
        val a = JkAssembler()
        val out = ArrayList<JkFrame>()
        // AT окремим шматком, далі по 20 байт — як сповіщення Bluetooth.
        out += a.feed(Jk.AT_MSG)
        for (chunk in (frame + ready).toList().chunked(20)) out += a.feed(chunk.toByteArray())
        assertEquals(1, out.size)
        assertEquals(Jk.TYPE_CELLS, out[0].type)
        assertTrue(out[0].bytes.contentEquals(frame))
        assertEquals(0, a.badSums)
        assertTrue(stream.size > Jk.FRAME_LEN)
    }

    @Test fun assembler_drops_frame_with_wrong_sum() {
        val frame = JkDemo.cells()
        frame[100] = (frame[100] + 1).toByte()
        val a = JkAssembler()
        assertTrue(a.feed(frame).isEmpty())
        assertEquals(1, a.badSums)
        // наступний правильний кадр проходить
        assertEquals(1, a.feed(JkDemo.cells()).size)
    }

    @Test fun device_info() {
        val d = JkParser.device(JkDemo.device())!!
        assertEquals("JK_B2A8S20P", d.model)
        assertEquals("11.XA", d.hw)
        assertEquals("11.48", d.sw)
        assertEquals(11, d.swMajor)
        assertEquals("Logic Power 305", d.name)
        assertEquals("DEMO0001", d.serial)
        assertEquals(10, JkDevice("", "", "10.08", "", "").swMajor)
        assertNull(JkParser.device(JkDemo.cells()))
    }

    @Test fun layout_is_detected_by_voltage_equals_cell_sum() {
        assertEquals(JkLayout.S32, JkParser.guessLayout(JkDemo.cells(JkLayout.S32)))
        assertEquals(JkLayout.S24, JkParser.guessLayout(JkDemo.cells(JkLayout.S24)))
        // навіть якщо версія плати каже інше — числа важливіші
        assertEquals(JkLayout.S24, JkParser.cells(JkDemo.cells(JkLayout.S24), swMajor = 11)!!.layout)
    }

    @Test fun both_layouts_give_the_same_numbers() {
        for (l in JkLayout.entries) {
            val c = JkParser.cells(JkDemo.cells(l))!!
            assertEquals("$l", listOf(3412, 3398, 3371, 3405), c.cellsMv)
            assertEquals("$l", listOf(62, 65, 71, 63), c.wireMohm)
            assertEquals(13.586, c.voltage, 1e-9)
            assertEquals(18.64, c.current, 1e-9)
            assertEquals(28.4, c.mosTemp!!, 1e-9)
            assertEquals("$l", listOf(19.6, 20.1), c.temperatures)
            assertEquals(0.42, c.balanceCurrent, 1e-9)
            assertEquals(1, c.balancerAction)
            assertEquals(78, c.soc)
            assertEquals(238.1, c.remainAh, 1e-9)
            assertEquals(305.0, c.fullAh, 1e-9)
            assertEquals(41, c.cycles)
            assertEquals(12_480.0, c.cycleCapacityAh, 1e-9)
            assertEquals(100, c.soh)
            assertEquals(3_456_000L, c.runtimeSec)
            assertTrue(c.chargeOn && c.dischargeOn && c.chargerPlugged)
            assertEquals(0L, c.errorMask)
        }
    }

    /** Місця байтів — окремо від демо, прямо з таблиці esphome-jk-bms (32S; 24S = на 32 байти раніше). */
    @Test fun byte_positions_match_the_open_source_tables() {
        val f = Jk.responseFrame(Jk.TYPE_CELLS) { b ->
            JkDemo.put16(b, 6, 3300); JkDemo.put16(b, 8, 3300)
            JkDemo.put32(b, 70, 0b11)
            JkDemo.put32(b, 150, 6600)
            JkDemo.put32(b, 158, -5_250) // віддає 5,25 А
            JkDemo.put32(b, 166, 1L shl 1) // гарячі ключі
            b[173] = 55
            b[199] = 1
        }
        val c = JkParser.cells(f)!!
        assertEquals(JkLayout.S32, c.layout)
        assertEquals(-5.25, c.current, 1e-9)
        assertEquals(55, c.soc)
        assertTrue(!c.chargeOn && c.dischargeOn)
        assertEquals(listOf(Protection.MOS_HOT), Protection.fromJkMask(c.errorMask))
        assertNull("датчиків нема (нулі = 0 °C лише якщо маска каже «є»)", c.mosTemp?.takeIf { it != 0.0 })
    }

    @Test fun errors_24s_are_16_bit_at_136() {
        val f = JkDemo.cells(JkLayout.S24)
        JkDemo.put16(f, 136, 1 shl 9) // холодно для заряду
        f[Jk.FRAME_LEN - 1] = Jk.sum(f, 0, Jk.FRAME_LEN - 1).toByte()
        val c = JkParser.cells(f)!!
        assertEquals(listOf(Protection.CHARGE_COLD), Protection.fromJkMask(c.errorMask))
    }

    @Test fun jk_error_bits_map_once_and_skip_non_battery_bits() {
        val bits = Protection.entries.filter { it.jkBit >= 0 }.map { it.jkBit }
        assertEquals("кожен біт JK — один захист", bits.size, bits.toSet().size)
        val covered = bits.toSet() + Protection.JK_IGNORED_BITS
        assertEquals("усі біти 0–28 з таблиці esphome відомі", (0..28).toSet(), covered.filter { it <= 28 }.toSet())
        assertEquals(listOf(Protection.CELL_OVER), Protection.fromJkMask(1L shl 4)) // «батарея повна» — як у JBD
        assertTrue(Protection.unknownJkBits(1L shl 19).isEmpty()) // «час змінити пароль» — не захист
        assertEquals(listOf(30), Protection.unknownJkBits(1L shl 30))
        // JBD не зачеплено: біти JK не з'являються в розборі JBD
        assertTrue(Protection.fromMask(0xFFFF).none { it.bit < 0 })
    }

    @Test fun snapshot_from_jk() {
        val dev = JkParser.device(JkDemo.device())
        val s = BatterySnapshot.fromJk(JkParser.cells(JkDemo.cells())!!, dev)
        assertEquals(4, s.cellCount)
        assertEquals(listOf(1, 3), s.balancingCells) // з найвищої №1 у найнижчу №3 (номери з 1, як у JBD)
        assertEquals("Logic Power 305", s.model)
        assertEquals("11.48", s.firmware)
        assertNotNull(s.jk)
        assertEquals("JK02_32S", s.jk!!.layout)
        assertTrue(s.jk!!.balancing)
        assertEquals(41, s.deltaMv)
        assertEquals(BatterySnapshot.Flow.CHARGING, s.flow)
    }

    @Test fun runtime_words() {
        assertEquals("40 діб 0 год", Fmt.runtime(3_456_000))
        assertEquals("1 доба 2 год", Fmt.runtime(93_600))
        assertEquals("2 доби 0 год", Fmt.runtime(172_800))
        assertEquals("5 год 12 хв", Fmt.runtime(18_720))
        assertEquals("12 хв", Fmt.runtime(720))
    }

    @Test fun charge_stage_only_on_new_boards() {
        val f = JkDemo.cells()
        f[280] = 2
        f[Jk.FRAME_LEN - 1] = Jk.sum(f, 0, Jk.FRAME_LEN - 1).toByte()
        assertNull(JkParser.cells(f, swMajor = 11)!!.chargeStage)
        assertEquals(2, JkParser.cells(f, swMajor = 15)!!.chargeStage)
    }
}

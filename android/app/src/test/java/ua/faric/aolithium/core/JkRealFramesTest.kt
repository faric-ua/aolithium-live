package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Розбір справжніх кадрів JK BMS (esphome-jk-bms, `JkRealFrames`) — до перевірки біля батареї в мами. Очікувані числа —
 * з коментарів esphome до кожного кадру: напруга, струм, найнижча / найвища комірка, температури, %, ємність, цикли,
 * ключі. Плати: JK-B2A24S20P (програма 10.07, розкладка 24S), JK_PB2A16S15P (14.20), JK_PB2A16S15P (15.38),
 * JK-PB2A16S20P (19.27) — розкладка 32S.
 */
class JkRealFramesTest {
    private val eps = 0.0011

    private class Expect(
        val layout: JkLayout, val cells: Int, val voltage: Double, val current: Double,
        val min: Pair<Int, Int>, val max: Pair<Int, Int>, // (№ комірки з 1, мВ)
        val t1: Double, val t2: Double, val mos: Double,
        val soc: Int, val soh: Int, val remainAh: Double?, val fullAh: Double?, val cycles: Int, val runtime: Long?,
        val charge: Boolean, val discharge: Boolean,
    )

    private fun check(frame: ByteArray, sw: Int, e: Expect) {
        val c = JkParser.cells(frame, sw)!!
        assertEquals("розкладка", e.layout, c.layout)
        assertEquals("кількість комірок", e.cells, c.cellsMv.size)
        assertEquals("напруга", e.voltage, c.voltage, eps)
        assertEquals("струм", e.current, c.current, eps)
        val lo = c.cellsMv.withIndex().minBy { it.value }
        val hi = c.cellsMv.withIndex().maxBy { it.value }
        assertEquals("найнижча", e.min.second, lo.value)
        assertEquals("найнижча №", e.min.first, c.cellsMv.indexOfFirst { it == lo.value } + 1)
        assertEquals("найвища", e.max.second, hi.value)
        assertEquals("найвища №", e.max.first, c.cellsMv.indexOfFirst { it == hi.value } + 1)
        assertEquals("T1", e.t1, c.temperatures[0], 0.051)
        assertEquals("T2", e.t2, c.temperatures[1], 0.051)
        assertEquals("ключі (MOS)", e.mos, c.mosTemp!!, 0.051)
        assertEquals("заряд %", e.soc, c.soc)
        assertEquals("здоров'я %", e.soh, c.soh)
        e.remainAh?.let { assertEquals("залишок", it, c.remainAh, eps) }
        e.fullAh?.let { assertEquals("повна", it, c.fullAh, eps) }
        assertEquals("цикли", e.cycles, c.cycles)
        e.runtime?.let { assertEquals("час роботи", it, c.runtimeSec) }
        assertEquals("заряд дозволено", e.charge, c.chargeOn)
        assertEquals("розряд дозволено", e.discharge, c.dischargeOn)
    }

    @Test fun device_frames() {
        val v10 = JkParser.device(JkRealFrames.deviceInfoJk0224sV10)!!
        assertEquals("JK-B2A24S20P", v10.model)
        assertEquals("10.07", v10.sw)
        assertEquals(10, v10.swMajor)
        val v19 = JkParser.device(JkRealFrames.deviceInfoJk0232sV19)!!
        assertEquals("JK-PB2A16S20P", v19.model)
        assertEquals(19, v19.swMajor)
        assertEquals(14, JkParser.device(JkRealFrames.deviceInfoJk0232sV11)!!.swMajor)
        assertEquals(15, JkParser.device(JkRealFrames.deviceInfoJk0232sV15)!!.swMajor)
    }

    @Test fun jk02_24s_v10() = check(JkRealFrames.cellInfoJk0224sV10, 10, Expect(
        JkLayout.S24, 24, 78.735, 0.0, 3 to 3279, 23 to 3285, 20.7, 20.7, 23.4,
        85, 100, 42.804, 50.0, 0, 269340, charge = true, discharge = false))

    @Test fun jk02_24s_v10_cold() = check(JkRealFrames.cellInfoJk0224sV10NegTemps, 10, Expect(
        JkLayout.S24, 13, 42.786, 0.0, 5 to 3288, 1 to 3292, -3.1, -2.9, 0.5,
        47, 100, null, null, 56, null, charge = false, discharge = false))

    @Test fun jk02_32s_v14_20() = check(JkRealFrames.cellInfoJk0232sV11, 14, Expect(
        JkLayout.S32, 16, 51.689, 0.0, 11 to 3216, 1 to 3246, 17.7, 17.7, 17.3,
        52, 100, 1.043, 2.0, 0, 107416, charge = true, discharge = true))

    @Test fun jk02_32s_v14_discharged() = check(JkRealFrames.cellInfoJk0232sV14, 14, Expect(
        JkLayout.S32, 16, 49.526, 0.0, 11 to 2851, 1 to 3175, 16.8, 17.0, 18.2,
        50, 100, 1.007, 2.0, 0, 124641, charge = true, discharge = true))

    @Test fun jk02_32s_v15_charging() = check(JkRealFrames.cellInfoJk0232sV15, 15, Expect(
        JkLayout.S32, 16, 53.224, 31.881, 13 to 3320, 16 to 3337, 13.4, 12.8, 12.9,
        25, 100, 49.286, 200.0, 9, 24530060, charge = true, discharge = true))

    @Test fun jk02_32s_v19_discharging() = check(JkRealFrames.cellInfoJk0232sV19, 19, Expect(
        JkLayout.S32, 16, 54.028, -0.727, 10 to 3374, 1 to 3381, 11.9, 12.7, 12.8,
        100, 100, 312.898, 314.0, 5, 3609276, charge = true, discharge = true))

    @Test fun layout_is_found_without_device_frame() {
        // Плата не встигла сказати «хто ти» — розкладку беремо з «напруга = сума комірок».
        assertEquals(JkLayout.S24, JkParser.cells(JkRealFrames.cellInfoJk0224sV10)!!.layout)
        assertEquals(JkLayout.S32, JkParser.cells(JkRealFrames.cellInfoJk0232sV19)!!.layout)
    }

    @Test fun real_frames_pass_the_checksum() {
        for (f in listOf(JkRealFrames.cellInfoJk0224sV10, JkRealFrames.cellInfoJk0232sV15, JkRealFrames.settingsJk0232sV19)) {
            val asm = JkAssembler()
            val got = f.toList().chunked(20).flatMap { asm.feed(it.toByteArray()) }
            assertTrue("кадр зібрано з шматків по 20 байт і сума зійшлась", got.size == 1)
        }
    }

    @Test fun settings_frame_v19() {
        // T28: справжній кадр налаштувань JK-PB2A16S20P, програма 19.27 (esphome-jk-bms).
        val st = JkParser.settings(JkRealFrames.settingsJk0232sV19)!!
        assertEquals(2600, st.cellUnderMv)
        assertEquals(3050, st.cellUnderBackMv)
        assertEquals(3650, st.cellOverMv)
        assertEquals(3448, st.cellOverBackMv)
        assertEquals(3450, st.soc100Mv)
        assertEquals(3000, st.soc0Mv)
        assertEquals(2500, st.powerOffMv)
        assertEquals(3450, st.balanceStartMv)
        assertEquals(3, st.balanceTriggerMv)
        assertEquals(2.0, st.balanceMaxA, eps)
        assertEquals(80.0, st.chargeMaxA, eps)
        assertEquals(100.0, st.dischargeMaxA, eps)
        assertEquals(70.0, st.chargeHotC, 0.01)
        assertEquals(2.0, st.chargeColdC, 0.01)
        assertEquals(5.0, st.chargeColdBackC, 0.01)
        assertEquals(80.0, st.mosHotC, 0.01)
        assertEquals(16, st.cellCount)
        assertEquals(314.0, st.capacityAh, eps)
        assertTrue(st.chargeSwitch && st.dischargeSwitch && st.balancerSwitch)
        assertEquals(st, JkSettings.decode(st.encode()))
        assertEquals("кадр комірок — не налаштування", null, JkParser.settings(JkRealFrames.cellInfoJk0232sV19))
        val view = JkSettingsView.groups(st)
        assertEquals(listOf("Комірка", "Струм", "Балансир", "Температура", "Інше"), view.map { it.title })
        assertTrue(view[0].rows.any { it.second == "3,650 В (знову з 3,448 В)" })
    }

    @Test fun demo_settings_go_through_the_same_parser() {
        val st = JkParser.settings(JkDemo.settings())!!
        assertEquals(4, st.cellCount)
        assertEquals(305.0, st.capacityAh, eps)
        // старі програми не мають «100 % / 0 %» — нулі не показуємо
        val old = st.copy(soc100Mv = 0, soc0Mv = 0)
        assertTrue(JkSettingsView.groups(old)[0].rows.none { it.first.startsWith("100 %") })
    }
}

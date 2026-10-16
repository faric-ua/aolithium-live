package ua.faric.aolithium.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадри з реальних плат JBD — з тестів відкритої бібліотеки aiobmsble
 * (https://github.com/patman15/aiobmsble, `tests/bms/test_jbd_bms.py`, `docs/jbd_bms.md`, Apache-2.0).
 */
class JbdTest {
    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** aiobmsble: 15,6 В · −2,87 А · 100 % · 4,98 А·год · 42 цикли · 3 датчики. */
    private val basicFrame = hex(
        "dd 03 00 1d 06 18 fe e1 01 f2 01 f4 00 2a 2c 7c 00 00 00 00 00 00 80 64 03 04 03 0b 8b 0b 8a 0b 84 f8 84 77",
    )

    /** aiobmsble: комірки 3,430 · 3,425 · 3,432 · 3,417 В. */
    private val cellsFrame = hex("dd 04 00 08 0d 66 0d 61 0d 68 0d 59 fe 3c 77")

    /** aiobmsble: назва плати «0123456789». */
    private val hwFrame = hex("dd 05 00 0a 30 31 32 33 34 35 36 37 38 39 fd e9 77")

    /**
     * Батарея Chins 300 А·год шле довшу відповідь: після температур ще 7 байт (aiobmsble `docs/jbd_bms.md`).
     * Приклад у тому документі скорочений (довжина 0x22, а даних 32 байти — сума не сходиться),
     * тому кадр збираємо самі з тих самих даних + 7 додаткових байт.
     */
    private val chinsFrame = Jbd.responseFrame(
        Jbd.CMD_BASIC,
        hex("05 32 00 00 4c ae 75 30 00 1b 31 2c 00 00 00 00 00 00 29 41 03 04 01 0b 3d 00 00 00 75 30 4c ae"),
    )

    private fun chunks(b: ByteArray, size: Int = 20) = b.toList().chunked(size).map { it.toByteArray() }

    private fun assemble(vararg parts: ByteArray): List<JbdFrame> {
        val a = JbdAssembler()
        return parts.flatMap { a.feed(it) }
    }

    @Test
    fun readCommandsAreExact() {
        assertArrayEquals(hex("dd a5 03 00 ff fd 77"), Jbd.readCommand(Jbd.CMD_BASIC))
        assertArrayEquals(hex("dd a5 04 00 ff fc 77"), Jbd.readCommand(Jbd.CMD_CELLS))
        assertArrayEquals(hex("dd a5 05 00 ff fb 77"), Jbd.readCommand(Jbd.CMD_HARDWARE))
        assertArrayEquals(hex("dd a5 aa 00 ff 56 77"), Jbd.readCommand(Jbd.CMD_ERROR_COUNTS))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeCommandsAreRefused() {
        Jbd.readCommand(0xE1) // 0xE1 вмикає/вимикає ключі — застосунок цього не робить
    }

    @Test
    fun basicFrameInBluetoothChunks() {
        val frames = assemble(*chunks(basicFrame).toTypedArray())
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(Jbd.CMD_BASIC, f.cmd)
        assertTrue(f.ok)
        val b = JbdParser.basic(f.data)!!
        assertEquals(15.60, b.voltage, 1e-9)
        assertEquals(-2.87, b.current, 1e-9)
        assertEquals(4.98, b.remainAh, 1e-9)
        assertEquals(5.00, b.fullAh, 1e-9)
        assertEquals(42, b.cycles)
        assertEquals(100, b.soc)
        assertTrue(b.chargeOn)
        assertTrue(b.dischargeOn)
        assertEquals(4, b.cellCount)
        assertEquals(listOf(22.4, 22.3, 21.7), b.temperatures.map { Math.round(it * 10) / 10.0 })
        assertEquals(0, b.protectionMask)
        assertEquals("8.0", b.firmware)
        assertEquals(Ymd(2022, 3, 28), b.made)
        assertEquals(emptyList<Int>(), b.balancingCells())
    }

    @Test
    fun cellsAndHardware() {
        val frames = assemble(cellsFrame, hwFrame)
        assertEquals(listOf(Jbd.CMD_CELLS, Jbd.CMD_HARDWARE), frames.map { it.cmd })
        assertEquals(listOf(3430, 3425, 3432, 3417), JbdParser.cells(frames[0].data))
        assertEquals("0123456789", JbdParser.hardware(frames[1].data))
    }

    @Test
    fun longerChinsFrameStillParses() {
        val f = assemble(*chunks(chinsFrame).toTypedArray()).single()
        val b = JbdParser.basic(f.data)!!
        assertEquals(13.30, b.voltage, 1e-9)
        assertEquals(196.30, b.remainAh, 1e-9)
        assertEquals(300.0, b.fullAh, 1e-9)
        assertEquals(27, b.cycles)
        assertEquals(65, b.soc)
        assertEquals(listOf(14.6), b.temperatures.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun twoFramesInOneChunk() {
        assertEquals(2, assemble(cellsFrame + hwFrame).size)
    }

    @Test
    fun garbageBeforeFrameIsSkipped() {
        assertEquals(1, assemble(hex("00 11 77"), cellsFrame).size)
    }

    @Test
    fun badChecksumIsRejectedAndNextFrameStillWorks() {
        val broken = cellsFrame.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val a = JbdAssembler()
        assertTrue(a.feed(broken).isEmpty())
        assertTrue(a.rejected > 0)
        assertEquals(1, a.feed(hwFrame).size)
    }

    @Test
    fun wrongTailIsRejected() {
        val broken = cellsFrame.copyOf().also { it[it.size - 1] = 0x78 }
        assertTrue(assemble(broken).isEmpty())
    }

    @Test
    fun lostChunkThenNewAnswerRecovers() {
        val first = chunks(basicFrame)[0] // друга половина загубилась
        val a = JbdAssembler()
        assertTrue(a.feed(first).isEmpty())
        val frames = a.feed(cellsFrame)
        assertEquals(1, frames.size)
        assertEquals(Jbd.CMD_CELLS, frames[0].cmd)
    }

    @Test
    fun errorStatusIsNotOk() {
        val f = assemble(Jbd.responseFrame(Jbd.CMD_BASIC, ByteArray(0), status = 0x80)).single()
        assertFalse(f.ok)
    }

    @Test
    fun tooShortBasicIsNull() {
        assertNull(JbdParser.basic(ByteArray(10)))
    }

    @Test
    fun bigBatteryScaleBit() {
        val data = Jbd.responseFrame(Jbd.CMD_BASIC, ByteArray(23).also {
            it[4] = 0x27; it[5] = 0x10 // 10000 → 1000,0 А·год у масштабі 0,1
            it[20] = (0x80 or 0x03).toByte()
        })
        val b = JbdParser.basic(assemble(data).single().data)!!
        assertEquals(1000.0, b.remainAh, 1e-9)
        assertTrue(b.chargeOn)
    }

    @Test
    fun errorCounts() {
        val f = assemble(DemoFrames.errorCounts()).single()
        val counts = JbdParser.errorCounts(f.data)!!
        assertEquals(11, counts.size)
        assertEquals("Коротке замикання" to 0, counts[0])
        assertEquals("Холодно для заряду" to 3, counts[6])
        assertNull(JbdParser.errorCounts(ByteArray(4)))
    }

    @Test
    fun productionDateNonsenseIsNull() {
        assertNull(Ymd.fromJbd(0))
        assertNull(Ymd.fromJbd(0x1F)) // місяць 0
        assertEquals("28.03.2022", Ymd(2022, 3, 28).toString())
    }

    @Test
    fun protectionBits() {
        val mask = (1 shl 5) or (1 shl 10) or (1 shl 14)
        assertEquals(listOf(Protection.CHARGE_COLD, Protection.SHORT_CIRCUIT), Protection.fromMask(mask))
        assertEquals(listOf(14), Protection.unknownBits(mask))
        assertTrue(Protection.fromMask(0).isEmpty())
    }

    @Test
    fun balancingCellsFromMask() {
        val data = ByteArray(23).also {
            it[13] = 0b0101 // комірки 1 і 3
            it[21] = 4
        }
        assertEquals(listOf(1, 3), JbdParser.basic(data)!!.balancingCells())
    }

    /** Демо «спокій» дає рівно ті числа, що Aolithium показав на відео faric. */
    @Test
    fun demoIdleMatchesAolithiumVideo() {
        val scene = DemoFrames.Scene.IDLE
        val frames = assemble(*chunks(DemoFrames.basic(scene)).toTypedArray()) + assemble(DemoFrames.cells(scene), DemoFrames.hardware())
        val basic = JbdParser.basic(frames[0].data)!!
        val s = BatterySnapshot.fromJbd(basic, JbdParser.cells(frames[1].data), JbdParser.hardware(frames[2].data), null)
        assertEquals("13,31 В", Fmt.volts(s.voltage))
        assertEquals("0,00 А", Fmt.amps(s.current))
        assertEquals(93, s.soc)
        assertEquals("86,31 А·год", Fmt.ampHours(s.remainAh))
        assertEquals("92,34 А·год", Fmt.ampHours(s.fullAh))
        assertEquals(123, s.cycles)
        assertEquals("12,3 °C", Fmt.celsius(s.temperatures.single()))
        assertEquals("3,328", Fmt.num(s.averageCellV!!, 3))
        assertEquals(BatterySnapshot.Flow.IDLE, s.flow)
        assertNull(s.hoursLeft)
        assertEquals("DEMO-JBD-4S-100A", s.model)
    }

    @Test
    fun demoScenes() {
        for (scene in DemoFrames.Scene.entries) {
            val b = JbdParser.basic(assemble(DemoFrames.basic(scene)).single().data)
            assertNotNull(scene.name, b)
        }
        val cold = JbdParser.basic(assemble(DemoFrames.basic(DemoFrames.Scene.COLD)).single().data)!!
        assertEquals(listOf(Protection.CHARGE_COLD), Protection.fromMask(cold.protectionMask))
        assertFalse(cold.chargeOn)
        assertEquals(-4.2, cold.temperatures.single(), 1e-9)
        val charging = JbdParser.basic(assemble(DemoFrames.basic(DemoFrames.Scene.CHARGING)).single().data)!!
        assertEquals(listOf(2), charging.balancingCells())
        assertEquals(8.5, charging.current, 1e-9)
    }
}

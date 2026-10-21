package ua.faric.aolithium.core

import kotlin.math.abs

/**
 * Протокол плати **JK BMS** (Jikong) — стоїть у батареї Logic Power faric (у мами). T5, v0.4.
 *
 * Лише Bluetooth LE: служба FFE0, канал FFE1 (і сповіщення, і запис).
 *
 * Команда — 20 байт: `AA 55 90 EB | код | довжина | значення… (до 13 байт, решта нулі) | сума`,
 * сума = (сума всіх 19 байтів) & 0xFF.
 * Відповідь — 300 байт: `55 AA EB 90 | тип | лічильник | дані… | сума` (сума байтів 0–298 & 0xFF у байті 299).
 * Тип: 0x01 — налаштування, 0x02 — комірки й стан, 0x03 — що це за плата.
 * Відповіді можуть іти шматками по 20 байт; модуль Bluetooth іноді додає `AT\r\n` — його відкидаємо.
 *
 * Дві розкладки кадру 0x02 (це формат, а не кількість комірок!):
 * - **JK02_32S** (програма плати 11 і новіша): до 32 комірок;
 * - **JK02_24S** (старіші): до 24 комірок — напруги комірок на тих самих місцях, решта полів на 16 / 32 байти раніше.
 * Яка саме — визначаємо самі: в одній розкладці напруга батареї дорівнює сумі комірок, в іншій — ні (тест `JkTest`).
 *
 * Звідки знання (свій код): aiobmsble `aiobmsble/bms/jikong_bms.py` і esphome-jk-bms `components/jk_bms_ble`
 * (обидва Apache-2.0). Подробиці — `docs/PROTOCOL_JK.md`. Без Android — перевіряється юніт-тестами.
 */
object Jk {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    val CMD_HEAD = byteArrayOf(0xAA.toByte(), 0x55, 0x90.toByte(), 0xEB.toByte())
    val RSP_HEAD = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())
    val AT_MSG = byteArrayOf(0x41, 0x54, 0x0D, 0x0A)

    /** «Хто ти»: модель, версії, назва, серійний номер (відповідь — тип 0x03). */
    const val CMD_DEVICE_INFO = 0x97

    /** «Комірки й стан» (відповідь — тип 0x02; далі плата сама шле їх по колу). */
    const val CMD_CELL_INFO = 0x96

    /** Лише ці дві команди ми шлемо. Усе інше в JK — запис налаштувань і ключів; у застосунку цього нема. */
    val READ_COMMANDS = setOf(CMD_DEVICE_INFO, CMD_CELL_INFO)

    const val TYPE_SETTINGS = 0x01
    const val TYPE_CELLS = 0x02
    const val TYPE_DEVICE = 0x03

    const val CMD_LEN = 20
    const val FRAME_LEN = 300

    /** Команда читання: `AA 55 90 EB <код> 00 00…00 <сума>`. */
    fun readCommand(cmd: Int): ByteArray {
        require(cmd in READ_COMMANDS) {
            tr("Застосунок лише читає плату: команда 0x%02X не з читання", "The app only reads the BMS: 0x%02X is not a read command")
                .format(cmd)
        }
        val frame = ByteArray(CMD_LEN)
        CMD_HEAD.copyInto(frame)
        frame[4] = cmd.toByte()
        frame[5] = 0
        frame[CMD_LEN - 1] = sum(frame, 0, CMD_LEN - 1).toByte()
        return frame
    }

    /** Сума байтів [from, to) & 0xFF. */
    fun sum(b: ByteArray, from: Int, to: Int): Int {
        var s = 0
        for (i in from until to) s += b[i].toInt() and 0xFF
        return s and 0xFF
    }

    fun startsWith(b: ByteArray, prefix: ByteArray, at: Int = 0): Boolean {
        if (b.size - at < prefix.size) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i]) return false
        return true
    }

    /** Відповідь цілком (для демо й тестів): заголовок, тип, лічильник, дані з байта 6, сума. */
    fun responseFrame(type: Int, fill: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(FRAME_LEN)
        RSP_HEAD.copyInto(f)
        f[4] = type.toByte()
        f[5] = 1
        fill(f)
        f[FRAME_LEN - 1] = sum(f, 0, FRAME_LEN - 1).toByte()
        return f
    }
}

/** Повний кадр відповіді JK (300 байт, сума вже перевірена). */
class JkFrame(val bytes: ByteArray) {
    val type: Int get() = bytes[4].toInt() and 0xFF
}

/**
 * Збирає кадри з шматків сповіщень. Новий кадр починається з `55 AA EB 90`; що не почалось так — відкидаємо.
 * Після 300 байт перевіряємо суму: не зійшлась — кадр пропадає (а не показуємо неправду).
 */
class JkAssembler {
    private var buf = ByteArray(0)
    var badSums = 0
        private set

    fun reset() {
        buf = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<JkFrame> {
        var data = chunk
        if (Jk.startsWith(data, Jk.AT_MSG)) data = data.copyOfRange(Jk.AT_MSG.size, data.size)
        if (data.isEmpty()) return emptyList()
        if (Jk.startsWith(data, Jk.RSP_HEAD)) buf = ByteArray(0)
        if (buf.isEmpty() && !Jk.startsWith(data, Jk.RSP_HEAD)) return emptyList() // хвіст чужого або ack — пропускаємо
        buf += data
        val out = ArrayList<JkFrame>()
        while (buf.size >= Jk.FRAME_LEN) {
            val frame = buf.copyOfRange(0, Jk.FRAME_LEN)
            val rest = buf.copyOfRange(Jk.FRAME_LEN, buf.size)
            if (Jk.sum(frame, 0, Jk.FRAME_LEN - 1) == (frame[Jk.FRAME_LEN - 1].toInt() and 0xFF)) out += JkFrame(frame) else badSums++
            // Після кадру може бути «готова» (AA 55 90 EB C8…) або `AT\r\n` — новий кадр лише з 55 AA EB 90.
            val next = (0..rest.size - Jk.RSP_HEAD.size).firstOrNull { Jk.startsWith(rest, Jk.RSP_HEAD, it) }
            buf = if (next == null) ByteArray(0) else rest.copyOfRange(next, rest.size)
        }
        return out
    }
}

/** Що плата каже про себе (кадр 0x03). Рядки — до першого невидимого символу. */
data class JkDevice(val model: String, val hw: String, val sw: String, val name: String, val serial: String) {
    /** «11.48» → 11; не число — 0. Від 11 — розкладка JK02_32S (так вирішує aiobmsble). */
    val swMajor: Int get() = sw.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}

/** Розкладка кадру 0x02. [shift] — на скільки байтів раніше лежать поля після опорів комірок. */
enum class JkLayout(val title: String, val maxCells: Int, val maskAt: Int, val wireAt: Int, val shift: Int) {
    S32("JK02_32S", 32, 70, 80, 0),
    S24("JK02_24S", 24, 54, 64, -32),
}

/** Усе з кадру 0x02 — числа, як їх дає плата. */
data class JkCells(
    val layout: JkLayout,
    val cellsMv: List<Int>,
    val wireMohm: List<Int>,
    val voltage: Double,
    val current: Double,
    val mosTemp: Double?,
    val temperatures: List<Double>,
    val errorMask: Long,
    val balanceCurrent: Double,
    /** 0 — балансир стоїть, 1 — вирівнює під час заряду, 2 — під час розряду (esphome-jk-bms). */
    val balancerAction: Int,
    val soc: Int,
    val remainAh: Double,
    val fullAh: Double,
    val cycles: Int,
    val cycleCapacityAh: Double,
    val soh: Int,
    val runtimeSec: Long,
    val chargeOn: Boolean,
    val dischargeOn: Boolean,
    val heatingOn: Boolean,
    val heatingCurrent: Double,
    val chargerPlugged: Boolean,
    /** 0 — основний заряд, 1 — дозаряд (absorption), 2 — підтримка (float); лише нові плати (програма 15+). */
    val chargeStage: Int?,
)

/**
 * T28 (v0.4.15): налаштування плати з кадру 0x01 — **лише показ**. Плата шле цей кадр сама у відповідь на «комірки й стан»
 * (0x96); ми нічого в неї не пишемо. Місця байтів — esphome-jk-bms (`decode_jk02_settings_`), однакові для 24S і 32S;
 * перевірено на справжніх кадрах (`JkRealFramesTest`). Нуль — «плата цього не має» (старі програми), не показуємо.
 */
data class JkSettings(
    /** Комірка: перерозряд — розряд вимикається / повертається; перезаряд — заряд вимикається / повертається, мВ. */
    val cellUnderMv: Int,
    val cellUnderBackMv: Int,
    val cellOverMv: Int,
    val cellOverBackMv: Int,
    /** Коли вважати 100 % і 0 % (мВ на комірку), коли плата вимикається зовсім. */
    val soc100Mv: Int,
    val soc0Mv: Int,
    val powerOffMv: Int,
    /** Балансир: починає з такої напруги комірки, коли різниця більша за поріг, струм до. */
    val balanceStartMv: Int,
    val balanceTriggerMv: Int,
    val balanceMaxA: Double,
    val chargeMaxA: Double,
    val dischargeMaxA: Double,
    /** Температури, °C: заряд — гаряче / повернення, холодно / повернення; розряд — гаряче / повернення; ключі (MOS). */
    val chargeHotC: Double,
    val chargeHotBackC: Double,
    val chargeColdC: Double,
    val chargeColdBackC: Double,
    val dischargeHotC: Double,
    val dischargeHotBackC: Double,
    val mosHotC: Double,
    val cellCount: Int,
    val capacityAh: Double,
    val chargeSwitch: Boolean,
    val dischargeSwitch: Boolean,
    val balancerSwitch: Boolean,
) {
    /** Для пам'яті телефона (останній стан батареї): «ключ=значення;…». */
    fun encode(): String = listOf(
        cellUnderMv, cellUnderBackMv, cellOverMv, cellOverBackMv, soc100Mv, soc0Mv, powerOffMv, balanceStartMv, balanceTriggerMv,
        balanceMaxA, chargeMaxA, dischargeMaxA, chargeHotC, chargeHotBackC, chargeColdC, chargeColdBackC, dischargeHotC,
        dischargeHotBackC, mosHotC, cellCount, capacityAh, if (chargeSwitch) 1 else 0, if (dischargeSwitch) 1 else 0,
        if (balancerSwitch) 1 else 0,
    ).joinToString(";")

    companion object {
        fun decode(raw: String?): JkSettings? {
            val v = raw?.split(';')?.map { it.toDoubleOrNull() ?: return null } ?: return null
            if (v.size != 24) return null
            return JkSettings(
                v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), v[7].toInt(),
                v[8].toInt(), v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19].toInt(), v[20],
                v[21] != 0.0, v[22] != 0.0, v[23] != 0.0,
            )
        }
    }
}

object JkParser {
    /** Датчика нема — плата пише −200,0 °C. */
    private const val NO_SENSOR = -2000

    fun device(f: ByteArray): JkDevice? {
        if (f.size < Jk.FRAME_LEN || (f[4].toInt() and 0xFF) != Jk.TYPE_DEVICE) return null
        return JkDevice(str(f, 6, 22), str(f, 22, 30), str(f, 30, 38), str(f, 46, 62), str(f, 86, 94))
    }

    /**
     * Кадр 0x02. Розкладку вибираємо самі: напруга батареї має дорівнювати сумі комірок (± 3 % — точність вимірювань).
     * Не підійшла жодна — беремо підказку з версії плати [swMajor] (як aiobmsble: < 11 — 24S), а без неї — 32S.
     */
    fun cells(f: ByteArray, swMajor: Int = 0): JkCells? {
        if (f.size < Jk.FRAME_LEN || (f[4].toInt() and 0xFF) != Jk.TYPE_CELLS) return null
        val layout = guessLayout(f) ?: if (swMajor in 1..10) JkLayout.S24 else JkLayout.S32
        return parse(f, layout, swMajor)
    }

    fun guessLayout(f: ByteArray): JkLayout? = JkLayout.entries
        .map { l -> l to mismatch(f, l) }
        .filter { it.second < 0.03 }
        .minByOrNull { it.second }?.first

    private fun mismatch(f: ByteArray, l: JkLayout): Double {
        val cells = cellList(f, l)
        val v = u32(f, 150 + l.shift) / 1000.0
        if (cells.isEmpty() || v <= 0.0) return Double.MAX_VALUE
        return abs(v - cells.sum() / 1000.0) / v
    }

    /** Комірки: стільки, скільки бітів у масці увімкнених; маски нема — усі ненульові. */
    private fun cellList(f: ByteArray, l: JkLayout): List<Int> {
        val n = java.lang.Long.bitCount(u32(f, l.maskAt)).coerceAtMost(l.maxCells)
        val all = (0 until l.maxCells).map { u16(f, 6 + it * 2) }
        return if (n > 0) all.take(n) else all.takeWhile { it > 0 }
    }

    fun parse(f: ByteArray, l: JkLayout, swMajor: Int = 0): JkCells {
        val o = l.shift
        val cells = cellList(f, l)
        val wire = (cells.indices).map { u16(f, l.wireAt + it * 2) }
        // Датчики (aiobmsble): маска присутніх 214(+o); 32S — №1, №2 на 162/164, №4, №5 на 256/258; 24S — на 130/132.
        val present = u16(f, 214 + o)
        val spots = if (l == JkLayout.S32) listOf(1 to 162, 2 to 164, 4 to 256, 5 to 258) else listOf(0 to 130, 1 to 132)
        val temps = spots.mapNotNull { (bit, pos) ->
            val raw = s16(f, pos)
            val on = present == 0 || present and (1 shl bit) != 0
            if (on && raw != NO_SENSOR && raw in -500..1500) raw / 10.0 else null
        }
        val mosRaw = s16(f, if (l == JkLayout.S32) 144 else 134)
        val errors = if (l == JkLayout.S32) u32(f, 166) else u16(f, 136).toLong()
        val stage = if (l == JkLayout.S32 && swMajor >= 15) (f[280].toInt() and 0xFF).takeIf { it in 0..2 } else null
        return JkCells(
            layout = l,
            cellsMv = cells,
            wireMohm = wire,
            voltage = u32(f, 150 + o) / 1000.0,
            current = s32(f, 158 + o) / 1000.0,
            mosTemp = if (mosRaw != NO_SENSOR && mosRaw in -500..1500) mosRaw / 10.0 else null,
            temperatures = temps,
            errorMask = errors,
            balanceCurrent = s16(f, 170 + o) / 1000.0,
            balancerAction = f[172 + o].toInt() and 0xFF,
            soc = (f[173 + o].toInt() and 0xFF).coerceAtMost(100),
            remainAh = u32(f, 174 + o) / 1000.0,
            fullAh = u32(f, 178 + o) / 1000.0,
            cycles = u32(f, 182 + o).toInt(),
            cycleCapacityAh = u32(f, 186 + o) / 1000.0,
            soh = (f[190 + o].toInt() and 0xFF).coerceAtMost(100),
            runtimeSec = u32(f, 194 + o),
            chargeOn = f[198 + o].toInt() != 0,
            dischargeOn = f[199 + o].toInt() != 0,
            heatingOn = f[215 + o].toInt() != 0,
            heatingCurrent = s16(f, 236 + o) / 1000.0,
            chargerPlugged = f[245 + o].toInt() != 0,
            chargeStage = stage,
        )
    }

    /** T28: кадр 0x01 → налаштування (лише показ). Не той кадр — null. */
    fun settings(f: ByteArray): JkSettings? {
        if (f.size < Jk.FRAME_LEN || (f[4].toInt() and 0xFF) != Jk.TYPE_SETTINGS) return null
        fun mv(i: Int) = u32(f, i).toInt()
        fun a(i: Int) = u32(f, i) / 1000.0
        fun c(i: Int) = s32(f, i) / 10.0
        return JkSettings(
            cellUnderMv = mv(10), cellUnderBackMv = mv(14), cellOverMv = mv(18), cellOverBackMv = mv(22),
            soc100Mv = mv(30), soc0Mv = mv(34), powerOffMv = mv(46),
            balanceStartMv = mv(138), balanceTriggerMv = mv(26), balanceMaxA = a(78),
            chargeMaxA = a(50), dischargeMaxA = a(62),
            chargeHotC = c(82), chargeHotBackC = c(86), chargeColdC = c(98), chargeColdBackC = c(102),
            dischargeHotC = c(90), dischargeHotBackC = c(94), mosHotC = c(106),
            cellCount = f[114].toInt() and 0xFF, capacityAh = a(130),
            chargeSwitch = f[118].toInt() != 0, dischargeSwitch = f[122].toInt() != 0, balancerSwitch = f[126].toInt() != 0,
        )
    }

    fun str(b: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until minOf(to, b.size)) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    // Усі числа JK — молодший байт першим.
    fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()
    fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    fun s32(b: ByteArray, i: Int): Long = u32(b, i).toInt().toLong()
}

/** Числа, яких нема в JBD (екрани 3–5 прев'ю): балансир, опір дротів, ключі, нагрів, здоров'я. */
data class JkExtra(
    val layout: String,
    val balanceCurrent: Double,
    val balancerAction: Int,
    val wireMohm: List<Int>,
    val mosTemp: Double?,
    val soh: Int,
    val cycleCapacityAh: Double,
    val runtimeSec: Long,
    val heatingOn: Boolean,
    val heatingCurrent: Double,
    val chargerPlugged: Boolean,
    val chargeStage: Int?,
    val hw: String?,
    val serial: String?,
    /** T28: налаштування плати (лише показ); плата ще не прислала — null. */
    val settings: JkSettings? = null,
) {
    val balancing: Boolean get() = balancerAction != 0

    val stageTitle: String? get() = when (chargeStage) {
        0 -> tr("основний заряд", "bulk charge")
        1 -> tr("дозаряд", "absorption")
        2 -> tr("підтримка (float)", "float")
        else -> null
    }
}

/** Демо JK без батареї: ті самі місця байтів, що й у справжньому кадрі — той самий розбір (CI, «Довідка»). */
object JkDemo {
    /** 4 комірки LiFePO4, ~305 А·год, заряд з працюючим балансиром (як у застосунку Logic Power). */
    val CELLS_MV = listOf(3412, 3398, 3371, 3405)
    val WIRE_MOHM = listOf(62, 65, 71, 63)

    fun device(sw: String = "11.48"): ByteArray = Jk.responseFrame(Jk.TYPE_DEVICE) { f ->
        putStr(f, 6, "JK_B2A8S20P")
        putStr(f, 22, "11.XA")
        putStr(f, 30, sw)
        putStr(f, 46, "Logic Power 305")
        putStr(f, 86, "DEMO0001")
    }

    fun cells(layout: JkLayout = JkLayout.S32): ByteArray = Jk.responseFrame(Jk.TYPE_CELLS) { f ->
        val o = layout.shift
        CELLS_MV.forEachIndexed { i, mv -> put16(f, 6 + i * 2, mv) }
        put32(f, layout.maskAt, (1L shl CELLS_MV.size) - 1)
        WIRE_MOHM.forEachIndexed { i, r -> put16(f, layout.wireAt + i * 2, r) }
        put16(f, if (layout == JkLayout.S32) 144 else 134, 284) // ключі 28,4 °C
        put32(f, 150 + o, CELLS_MV.sum().toLong())
        put32(f, 158 + o, 18_640) // +18,64 А — заряджається
        if (layout == JkLayout.S32) {
            put16(f, 162, 196); put16(f, 164, 201); put16(f, 256, -2000); put16(f, 258, -2000)
        } else {
            put16(f, 130, 196); put16(f, 132, 201)
        }
        // помилок нема: 32S — 4 байти з 166, 24S — 2 байти з 136 (134–135 у 24S — це температура ключів)
        put16(f, 170 + o, 420) // балансир 0,42 А
        f[172 + o] = 1
        f[173 + o] = 78
        put32(f, 174 + o, 238_100)
        put32(f, 178 + o, 305_000)
        put32(f, 182 + o, 41)
        put32(f, 186 + o, 12_480_000)
        f[190 + o] = 100
        put32(f, 194 + o, 3_456_000) // 40 діб
        f[198 + o] = 1
        f[199 + o] = 1
        put16(f, 214 + o, if (layout == JkLayout.S32) 0b0000_0111 else 0b0000_0111)
        f[215 + o] = 0
        f[245 + o] = 1
        if (layout == JkLayout.S32) f[280] = 0
    }

    /** Налаштування демо (T28): LiFePO4 4S ~305 А·год, як могло б бути в Logic Power. */
    fun settings(): ByteArray = Jk.responseFrame(Jk.TYPE_SETTINGS) { f ->
        put32(f, 10, 2_600); put32(f, 14, 2_900); put32(f, 18, 3_650); put32(f, 22, 3_400)
        put32(f, 26, 10); put32(f, 30, 3_450); put32(f, 34, 2_900); put32(f, 46, 2_500)
        put32(f, 50, 100_000); put32(f, 62, 150_000); put32(f, 78, 2_000)
        put32(f, 82, 650); put32(f, 86, 600); put32(f, 90, 700); put32(f, 94, 650)
        put32(f, 98, 0); put32(f, 102, 50); put32(f, 106, 800); put32(f, 110, 700)
        f[114] = 4; f[118] = 1; f[122] = 1; f[126] = 1
        put32(f, 130, 305_000); put32(f, 138, 3_400)
    }

    private fun putStr(f: ByteArray, at: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(f, at)
    fun put16(f: ByteArray, at: Int, v: Int) {
        f[at] = v.toByte(); f[at + 1] = (v shr 8).toByte()
    }
    fun put32(f: ByteArray, at: Int, v: Long) {
        put16(f, at, (v and 0xFFFF).toInt()); put16(f, at + 2, ((v shr 16) and 0xFFFF).toInt())
    }
}

/**
 * T28: що показати з налаштувань JK — рядки «назва — значення» групами. Нулі (старі програми плати цього не мають)
 * не показуємо. Без Android — тест `JkRealFramesTest`.
 */
object JkSettingsView {
    data class Group(val title: String, val rows: List<Pair<String, String>>)

    private fun pair(main: String, back: String) = tr("$main (знову з $back)", "$main (again from $back)")

    fun groups(s: JkSettings): List<Group> {
        val cell = ArrayList<Pair<String, String>>()
        if (s.cellOverMv > 0) cell += tr("Заряд вимикає на", "Charge stops at") to pair(Fmt.cellVolts(s.cellOverMv), Fmt.cellVolts(s.cellOverBackMv))
        if (s.cellUnderMv > 0) cell += tr("Розряд вимикає на", "Discharge stops at") to pair(Fmt.cellVolts(s.cellUnderMv), Fmt.cellVolts(s.cellUnderBackMv))
        if (s.soc100Mv > 0) cell += tr("100 % — від", "100 % — from") to Fmt.cellVolts(s.soc100Mv)
        if (s.soc0Mv > 0) cell += tr("0 % — на", "0 % — at") to Fmt.cellVolts(s.soc0Mv)
        if (s.powerOffMv > 0) cell += tr("Плата вимикається на", "BMS powers off at") to Fmt.cellVolts(s.powerOffMv)

        val current = ArrayList<Pair<String, String>>()
        if (s.chargeMaxA > 0) current += tr("Заряд — до", "Charge — up to") to Fmt.num(s.chargeMaxA, 0) + " " + Fmt.A
        if (s.dischargeMaxA > 0) current += tr("Розряд — до", "Discharge — up to") to Fmt.num(s.dischargeMaxA, 0) + " " + Fmt.A

        val bal = ArrayList<Pair<String, String>>()
        bal += tr("Балансир", "Balancer") to if (s.balancerSwitch) tr("увімкнено", "on") else tr("вимкнено", "off")
        if (s.balanceStartMv > 0) bal += tr("Працює від", "Works from") to Fmt.cellVolts(s.balanceStartMv)
        if (s.balanceTriggerMv > 0) bal += tr("Коли різниця більша за", "When the difference exceeds") to Fmt.millivolts(s.balanceTriggerMv)
        if (s.balanceMaxA > 0) bal += tr("Струм — до", "Current — up to") to Fmt.num(s.balanceMaxA, 1) + " " + Fmt.A

        val temp = ArrayList<Pair<String, String>>()
        if (s.chargeHotC != 0.0) temp += tr("Заряд: гаряче", "Charge: hot") to pair(Fmt.celsius(s.chargeHotC), Fmt.celsius(s.chargeHotBackC))
        if (s.chargeColdC != 0.0 || s.chargeColdBackC != 0.0) temp += tr("Заряд: холодно", "Charge: cold") to pair(Fmt.celsius(s.chargeColdC), Fmt.celsius(s.chargeColdBackC))
        if (s.dischargeHotC != 0.0) temp += tr("Розряд: гаряче", "Discharge: hot") to pair(Fmt.celsius(s.dischargeHotC), Fmt.celsius(s.dischargeHotBackC))
        if (s.mosHotC != 0.0) temp += tr("Ключі (MOS): гаряче", "Switches (MOS): hot") to Fmt.celsius(s.mosHotC)

        val other = ArrayList<Pair<String, String>>()
        if (s.capacityAh > 0) other += tr("Ємність (налаштована)", "Capacity (set)") to Fmt.num(s.capacityAh, 0) + " " + Fmt.AH
        if (s.cellCount > 0) other += tr("Комірок (налаштовано)", "Cells (set)") to "${s.cellCount}"
        other += tr("Ключ заряду", "Charge switch") to if (s.chargeSwitch) tr("увімкнено", "on") else tr("вимкнено", "off")
        other += tr("Ключ розряду", "Discharge switch") to if (s.dischargeSwitch) tr("увімкнено", "on") else tr("вимкнено", "off")

        return listOf(
            Group(tr("Комірка", "Cell"), cell),
            Group(tr("Струм", "Current"), current),
            Group(tr("Балансир", "Balancer"), bal),
            Group(tr("Температура", "Temperature"), temp),
            Group(tr("Інше", "Other"), other),
        ).filter { it.rows.isNotEmpty() }
    }
}

package ua.faric.aolithium.core

/**
 * Протокол плати JBD (Jiabaida, «Xiaoxiang») — саме вона стоїть у батареї Aolithium.
 *
 * Лише Bluetooth LE: служба FF00, відповіді приходять сповіщеннями з FF01, команди пишуться в FF02.
 * Кадр (і команда, і відповідь):
 *
 *     DD | A5 (запит) або код (відповідь) | код або стан | довжина | дані… | сума 2 байти | 77
 *
 * Сума = 0x10000 − (сума байтів від коду/стану до кінця даних).
 *
 * Звідки знання (свій код, без коду Aolithium): відкриті бібліотеки aiobmsble (`jbd_bms.py`, Apache-2.0)
 * і esphome-jbd-bms (`jbd_bms_ble.cpp`). Подробиці — `docs/PROTOCOL_JBD.md`.
 *
 * Без Android — перевіряється юніт-тестами.
 */
object Jbd {
    const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"

    const val START = 0xDD
    const val READ = 0xA5
    const val END = 0x77

    /** Основне: напруга, струм, ємність, цикли, захисти, заряд %, ключі, температури. */
    const val CMD_BASIC = 0x03

    /** Напруга кожної комірки. */
    const val CMD_CELLS = 0x04

    /** Назва / версія плати (рядок). */
    const val CMD_HARDWARE = 0x05

    /** Скільки разів спрацьовував кожен захист за весь час (є не на всіх платах). */
    const val CMD_ERROR_COUNTS = 0xAA

    /** Лише ці команди ми читаємо. Запис у плату (ключі, налаштування) у застосунку не робиться. */
    val READ_COMMANDS = setOf(CMD_BASIC, CMD_CELLS, CMD_HARDWARE, CMD_ERROR_COUNTS)

    /** Найменший кадр: DD, код, стан, довжина, сума ×2, 77. */
    const val MIN_FRAME = 7

    /** Сума JBD для байтів [from, to). */
    fun checksum(bytes: ByteArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from until to) sum += bytes[i].toInt() and 0xFF
        return (0x10000 - sum) and 0xFFFF
    }

    /** Команда читання: `DD A5 <код> 00 <сума> 77`, напр. `DD A5 03 00 FF FD 77`. */
    fun readCommand(cmd: Int): ByteArray {
        require(cmd in READ_COMMANDS) {
            tr("Застосунок лише читає плату: команда 0x%02X не з читання", "The app only reads the BMS: 0x%02X is not a read command")
                .format(cmd)
        }
        val frame = ByteArray(7)
        frame[0] = START.toByte()
        frame[1] = READ.toByte()
        frame[2] = cmd.toByte()
        frame[3] = 0
        val crc = checksum(frame, 2, 4)
        frame[4] = (crc shr 8).toByte()
        frame[5] = crc.toByte()
        frame[6] = END.toByte()
        return frame
    }

    /** Зібрати кадр відповіді (для тестів і демо-режиму). */
    fun responseFrame(cmd: Int, data: ByteArray, status: Int = 0): ByteArray {
        require(data.size <= 0xFF)
        val frame = ByteArray(data.size + MIN_FRAME)
        frame[0] = START.toByte()
        frame[1] = cmd.toByte()
        frame[2] = status.toByte()
        frame[3] = data.size.toByte()
        data.copyInto(frame, 4)
        val crc = checksum(frame, 2, 4 + data.size)
        frame[4 + data.size] = (crc shr 8).toByte()
        frame[5 + data.size] = crc.toByte()
        frame[6 + data.size] = END.toByte()
        return frame
    }
}

/** Розібраний кадр відповіді плати: код команди, стан (0 — гаразд) і самі дані. */
class JbdFrame(val cmd: Int, val status: Int, val data: ByteArray) {
    val ok: Boolean get() = status == 0
}

/**
 * Збирає кадри з шматків, що приходять сповіщеннями Bluetooth.
 *
 * Сповіщення зазвичай по 20 байт, тож один кадр «Основне» (36+ байт) приходить двома-трьома шматками.
 * Буває й навпаки — шматок губиться. Тому: кадр видаємо лише цілий, з правильним кінцем і сумою;
 * зіпсоване відкидаємо; новий початок кадру посеред незавершеного — починаємо заново.
 */
class JbdAssembler(private val maxBytes: Int = 512) {
    private val buf = ArrayList<Byte>()

    /** Скільки байтів чекають (для журналу). */
    val pending: Int get() = buf.size

    /** Скільки зіпсованих кадрів відкинуто (для журналу й діагностики). */
    var rejected: Int = 0
        private set

    fun reset() {
        buf.clear()
    }

    fun feed(chunk: ByteArray): List<JbdFrame> {
        if (looksLikeStart(chunk) && buf.isNotEmpty() && !hasCompleteFrame()) {
            // Попередній кадр так і не завершився (загубився шматок) — нова відповідь важливіша.
            rejected++
            buf.clear()
        }
        for (b in chunk) buf.add(b)
        if (buf.size > maxBytes) {
            rejected++
            buf.clear()
            return emptyList()
        }
        val out = ArrayList<JbdFrame>()
        while (true) {
            // До першого DD — сміття.
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == Jbd.START }
            if (start < 0) {
                buf.clear()
                break
            }
            if (start > 0) repeat(start) { buf.removeAt(0) }
            if (buf.size < 4) break
            val len = buf[3].toInt() and 0xFF
            val total = len + Jbd.MIN_FRAME
            if (buf.size < total) break
            val frame = ByteArray(total) { buf[it] }
            if (valid(frame)) {
                out += JbdFrame(frame[1].toInt() and 0xFF, frame[2].toInt() and 0xFF, frame.copyOfRange(4, 4 + len))
                repeat(total) { buf.removeAt(0) }
            } else {
                rejected++
                buf.removeAt(0) // шукати наступний DD
            }
        }
        return out
    }

    private fun hasCompleteFrame(): Boolean =
        buf.size >= 4 && buf.size >= (buf[3].toInt() and 0xFF) + Jbd.MIN_FRAME

    private fun looksLikeStart(c: ByteArray): Boolean =
        c.size >= 3 && (c[0].toInt() and 0xFF) == Jbd.START &&
            (c[1].toInt() and 0xFF) in Jbd.READ_COMMANDS &&
            ((c[2].toInt() and 0xFF) == 0x00 || (c[2].toInt() and 0xFF) == 0x80)

    private fun valid(f: ByteArray): Boolean {
        val len = f[3].toInt() and 0xFF
        val endAt = 4 + len + 2
        if ((f[endAt].toInt() and 0xFF) != Jbd.END) return false
        if ((f[1].toInt() and 0xFF) !in Jbd.READ_COMMANDS) return false
        val crc = ((f[4 + len].toInt() and 0xFF) shl 8) or (f[5 + len].toInt() and 0xFF)
        return crc == Jbd.checksum(f, 2, 4 + len)
    }
}

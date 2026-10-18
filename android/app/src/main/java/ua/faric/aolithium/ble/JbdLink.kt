package ua.faric.aolithium.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.UUID
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Jbd
import ua.faric.aolithium.core.JbdAssembler
import ua.faric.aolithium.core.JbdBasic
import ua.faric.aolithium.core.JbdFrame
import ua.faric.aolithium.core.JbdParser
import ua.faric.aolithium.core.Signal

/**
 * Зв'язок з платою JBD по Bluetooth LE: лише **читання**.
 *
 * Порядок: підключитись → знайти службу FF00 → увімкнути сповіщення FF01 → по колу кожні [POLL_MS]:
 * «Основне» (0x03) → «Комірки» (0x04); один раз на підключення ще «Назва плати» (0x05) і
 * «Лічильники захистів» (0xAA) — їх може не бути, тоді просто пропускаємо.
 *
 * Уся робота з Bluetooth — в одному своєму потоці (Android не любить паралельних операцій GATT);
 * колбеки Android лише перекладають подію в цей потік.
 */
class JbdLink(context: Context, override val address: String) : BatteryLink {
    private val app = context.applicationContext
    private val thread = HandlerThread("jbd-link").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    override var listener: BatteryLink.Listener? = null
    @Volatile override var lastStatus: LinkStatus = LinkStatus.IDLE
        private set
    @Volatile override var lastSnapshot: BatterySnapshot? = null
        private set
    @Volatile override var lastSnapshotAt: Long = 0
        private set

    private var running = false
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val assembler = JbdAssembler()
    private val queue = ArrayDeque<Int>()
    private var expected = -1
    private var basic: JbdBasic? = null
    private var cells: List<Int> = emptyList()
    private var model: String? = null
    private var errorCounts: List<Pair<String, Int>>? = null
    private var askedOnce = false
    private var reading = false
    private var silentCycles = 0
    private var reconnects = 0
    private var everLive = false
    private var attempt = 0
    private var pendingConnect = false
    private val short = BmsMatch.shortAddress(address)

    private val connectTimeout = Runnable { failConnect("Батарея не відповіла за ${CONNECT_TIMEOUT_MS / 1000} с.") }
    private val answerTimeout = Runnable { onAnswerTimeout() }
    private val nextCycle = Runnable { cycle() }

    override fun start() {
        h.post {
            if (running) return@post
            running = true
            reconnects = 0
            connect()
        }
    }

    override fun stop() {
        h.post {
            running = false
            h.removeCallbacksAndMessages(null)
            closeGatt()
            status(LinkStatus.Phase.IDLE)
            thread.quitSafely()
        }
    }

    // ---------- підключення ----------

    /**
     * Підключення (v0.1.1, після коду 133 на A26 18.10):
     * 1. Беремо пристрій **з пошуку** (Android знає тип адреси й що батарея в ефірі). Якщо його нема
     *    (відкрили з «Моїх батарей») — спершу коротко шукаємо саме цю адресу.
     * 2. Пауза після пошуку — Samsung дає 133, якщо підключатись одразу після зупинки пошуку.
     * 3. `connectGatt` — з головного потоку (відома вимога деяких Samsung).
     * 4. Остання спроба — «чекати батарею» (autoConnect): Android сам підключиться, щойно побачить її.
     */
    private fun connect() {
        if (!running) return
        val adapter = BtAccess.adapter(app)
        if (adapter == null || !BtAccess.bluetoothOn(app)) {
            status(LinkStatus.Phase.FAILED, "Bluetooth вимкнено. Увімкни його й натисни «Підключити ще раз».")
            return
        }
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address.uppercase())) {
            running = false
            status(LinkStatus.Phase.FAILED, "Неправильна адреса батареї ($address).")
            return
        }
        resetSession()
        status(LinkStatus.Phase.CONNECTING)
        val token = ++attempt
        val known = BleScanner.seen[address]
        if (known != null) {
            LinkLog.add("$short: підключаюсь (пристрій з пошуку), спроба ${reconnects + 1}" +
                (BleScanner.lastRssi[address]?.let { ", ${Signal.describe(it)}" } ?: ""))
            h.postDelayed({ connectTo(known, token) }, AFTER_SCAN_MS)
            return
        }
        LinkLog.add("$short: шукаю батарею в ефірі перед підключенням…")
        main.post {
            BleScanner.findOne(app, address, FIND_MS) { device ->
                h.post {
                    if (!running || token != attempt) return@post
                    if (device == null) {
                        LinkLog.add("$short: за ${FIND_MS / 1000} с у пошуку не з'явилась")
                        running = false
                        status(LinkStatus.Phase.FAILED, NOT_ADVERTISING_HINT)
                    } else {
                        LinkLog.add("$short: знайшлась, підключаюсь")
                        h.postDelayed({ connectTo(device, token) }, AFTER_SCAN_MS)
                    }
                }
            }
        }
    }

    private fun connectTo(device: BluetoothDevice, token: Int) {
        if (!running || token != attempt) return
        val wait = reconnects >= MAX_RECONNECTS // остання спроба — autoConnect
        if (wait) LinkLog.add("$short: остання спроба — чекаю, доки Android сам підключиться (до ${WAIT_CONNECT_MS / 1000} с)")
        pendingConnect = true
        main.post {
            val g = try {
                if (Build.VERSION.SDK_INT >= 23) device.connectGatt(app, wait, callback, BluetoothDevice.TRANSPORT_LE)
                else device.connectGatt(app, wait, callback)
            } catch (e: SecurityException) {
                null
            }
            h.post {
                pendingConnect = false
                if (g == null) {
                    running = false
                    status(LinkStatus.Phase.FAILED, "Android не дав дозволу на Bluetooth. Дозволь на головному екрані застосунку.")
                    return@post
                }
                if (!running || token != attempt) {
                    safe { g.close() }
                    return@post
                }
                if (gatt == null) gatt = g
                h.postDelayed(connectTimeout, if (wait) WAIT_CONNECT_MS else CONNECT_TIMEOUT_MS)
            }
        }
    }

    private fun resetSession() {
        assembler.reset()
        queue.clear()
        expected = -1
        askedOnce = false
        reading = false
        silentCycles = 0
        writeChar = null
    }

    private fun failConnect(reason: String) {
        LinkLog.add("$short: $reason")
        closeGatt()
        retryOrGiveUp(reason)
    }

    private fun retryOrGiveUp(why: String) {
        if (!running) return
        // 18.10: на A26 при сигналі −97 dBm — код 133 щоразу. Кажемо прямо, що батарея далеко.
        val reason = BleScanner.lastRssi[address]?.takeIf { Signal.tooFar(it) }
            ?.let { "$why Батарея далеко (${Signal.describe(it)}) — підійди ближче, на 1–3 м." } ?: why
        if (reconnects < MAX_RECONNECTS) {
            reconnects++
            status(LinkStatus.Phase.LOST, "$reason Пробую ще раз ($reconnects з $MAX_RECONNECTS)…")
            h.postDelayed({ connect() }, RECONNECT_DELAY_MS * reconnects)
        } else {
            status(LinkStatus.Phase.FAILED, "$reason $BUSY_HINT")
        }
    }

    private fun closeGatt() {
        h.removeCallbacks(connectTimeout)
        h.removeCallbacks(answerTimeout)
        h.removeCallbacks(nextCycle)
        val g = gatt ?: return
        gatt = null
        safe { g.disconnect() }
        safe { g.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            h.post { onConnectionChanged(g, statusCode, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            h.post { onServices(g, statusCode) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            h.post { if (g == gatt) startReading() }
        }

        // Android 13+: значення приходить окремим параметром.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            h.post { if (g == gatt) onBytes(copy) }
        }

        // Android 8–12: значення — у самій характеристиці.
        @Deprecated("Android 13+ кличе версію з value")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val copy = c.value?.copyOf() ?: return
            h.post { if (g == gatt) onBytes(copy) }
        }
    }

    private fun onConnectionChanged(g: BluetoothGatt, statusCode: Int, newState: Int) {
        if (gatt == null && pendingConnect) gatt = g // відповідь Android прийшла раніше, ніж connectGatt повернувся
        if (g != gatt) {
            safe { g.close() }
            return
        }
        when {
            newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS -> {
                h.removeCallbacks(connectTimeout)
                LinkLog.add("$short: підключено, шукаю службу FF00")
                status(LinkStatus.Phase.READING)
                // Невелика пауза перед пошуком служб — відома порада для Samsung/Huawei.
                h.postDelayed({ if (g == gatt && safe { g.discoverServices() } != true) failConnect("Не вдалось прочитати служби батареї.") }, 600)
            }
            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                LinkLog.add("$short: відключено (код Android $statusCode" + (if (everLive) ", після даних)" else if (expected == -1 && !reading) ", ще до з'єднання)" else ", під час читання)"))
                closeGatt()
                val reason = if (statusCode == 133 || statusCode == 8 || statusCode == 62) {
                    "Android не зміг утримати зв'язок (код $statusCode)."
                } else {
                    "Батарея відключилась."
                }
                if (everLive) reconnects = 0 // була на зв'язку — пробуємо знову з нуля
                everLive = false
                retryOrGiveUp(reason)
            }
        }
    }

    private fun onServices(g: BluetoothGatt, statusCode: Int) {
        if (g != gatt) return
        val service = g.getService(UUID.fromString(Jbd.SERVICE_UUID))
        if (statusCode != BluetoothGatt.GATT_SUCCESS || service == null) {
            LinkLog.add("$short: служби FF00 немає (код $statusCode); є: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
            running = false
            closeGatt()
            status(LinkStatus.Phase.FAILED, "Це не плата JBD: у неї немає потрібної служби Bluetooth (FF00).")
            return
        }
        val notify = service.getCharacteristic(UUID.fromString(Jbd.NOTIFY_UUID))
        val write = service.getCharacteristic(UUID.fromString(Jbd.WRITE_UUID))
        if (notify == null || write == null) {
            running = false
            closeGatt()
            status(LinkStatus.Phase.FAILED, "У плати немає каналів FF01/FF02 — це не JBD або інша версія.")
            return
        }
        writeChar = write
        safe { g.setCharacteristicNotification(notify, true) }
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) {
            startReading()
            return
        }
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = safe {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } == true
        if (!ok) startReading() // деякі плати шлють і без цього
        // Буває, що Android не повідомляє «записано» — тоді через 1,5 с починаємо читати й так.
        h.postDelayed({ if (g == gatt) startReading() }, 1_500)
    }

    // ---------- читання по колу ----------

    private fun startReading() {
        if (!running || reading) return
        reading = true
        LinkLog.add("$short: сповіщення ввімкнено, читаю")
        cycle()
    }

    private fun cycle() {
        if (!running || gatt == null) return
        queue.clear()
        queue.addLast(Jbd.CMD_BASIC)
        queue.addLast(Jbd.CMD_CELLS)
        if (!askedOnce) {
            queue.addLast(Jbd.CMD_HARDWARE)
            queue.addLast(Jbd.CMD_ERROR_COUNTS)
        }
        sendNext()
    }

    private fun sendNext() {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (queue.isEmpty()) {
            cycleDone()
            return
        }
        val cmd = queue.removeFirst()
        expected = cmd
        assembler.reset()
        val bytes = Jbd.readCommand(cmd)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = safe {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } == true
        LinkLog.add("→ " + LinkLog.hex(bytes) + if (ok) "" else "  (Android не прийняв запис)")
        h.postDelayed(answerTimeout, ANSWER_TIMEOUT_MS)
    }

    private fun onBytes(bytes: ByteArray) {
        LinkLog.add("← " + LinkLog.hex(bytes))
        for (f in assembler.feed(bytes)) onFrame(f)
    }

    private fun onFrame(f: JbdFrame) {
        if (f.cmd != expected) {
            LinkLog.add("   (не та відповідь: 0x%02x, чекав 0x%02x)".format(f.cmd, expected))
            return
        }
        h.removeCallbacks(answerTimeout)
        expected = -1
        if (f.ok) {
            when (f.cmd) {
                Jbd.CMD_BASIC -> basic = JbdParser.basic(f.data) ?: basic
                Jbd.CMD_CELLS -> cells = JbdParser.cells(f.data)
                Jbd.CMD_HARDWARE -> model = JbdParser.hardware(f.data)
                Jbd.CMD_ERROR_COUNTS -> errorCounts = JbdParser.errorCounts(f.data)
            }
        } else {
            LinkLog.add("   плата відповіла «помилка» (стан 0x%02x)".format(f.status))
        }
        sendNext()
    }

    private fun onAnswerTimeout() {
        val cmd = expected
        expected = -1
        LinkLog.add("   немає відповіді на 0x%02x".format(cmd))
        if (cmd == Jbd.CMD_HARDWARE || cmd == Jbd.CMD_ERROR_COUNTS) {
            sendNext() // необов'язкові — не на всіх платах
            return
        }
        if (cmd == Jbd.CMD_CELLS) {
            sendNext()
            return
        }
        // «Основне» не прийшло — цей круг пропадає.
        queue.clear()
        silentCycles++
        if (silentCycles >= SILENT_CYCLES_LIMIT) {
            status(LinkStatus.Phase.NO_ANSWER, NO_ANSWER_HINT)
        }
        h.postDelayed(nextCycle, POLL_MS)
    }

    private fun cycleDone() {
        askedOnce = true
        val b = basic
        if (b != null) {
            silentCycles = 0
            everLive = true
            reconnects = 0
            val snap = BatterySnapshot.fromJbd(b, cells, model, errorCounts)
            val now = System.currentTimeMillis()
            lastSnapshot = snap
            lastSnapshotAt = now
            main.post { listener?.onSnapshot(snap, now) }
            status(LinkStatus.Phase.LIVE)
        }
        h.postDelayed(nextCycle, POLL_MS)
    }

    private fun status(phase: LinkStatus.Phase, message: String = "") {
        val s = LinkStatus(phase, message)
        if (s == lastStatus) return
        lastStatus = s
        main.post { listener?.onStatus(s) }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        LinkLog.add("$short: Android заборонив (${e.message})")
        null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLL_MS = 2_000L
        const val ANSWER_TIMEOUT_MS = 2_500L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECTS = 3
        const val AFTER_SCAN_MS = 600L
        const val FIND_MS = 10_000L
        const val WAIT_CONNECT_MS = 30_000L

        const val NOT_ADVERTISING_HINT = "Батарея не з'являється в ефірі. Вона поруч? Aolithium на інших телефонах закритий? " +
            "Поки плата з кимось на зв'язку, її не видно. Закрий інші застосунки й натисни «Підключити ще раз»."
        const val SILENT_CYCLES_LIMIT = 3

        const val BUSY_HINT = "Можливо, батарея зараз підключена до іншого телефона чи до застосунку Aolithium — " +
            "плата тримає лише одне підключення. Закрий той застосунок і натисни «Підключити ще раз»."
        const val NO_ANSWER_HINT = "Підключились, але плата не відповідає на запити. Можливо, на ній стоїть пароль " +
            "(Aolithium його не питав, тож навряд чи). Надішли Claude «Журнал зв'язку»."
    }
}

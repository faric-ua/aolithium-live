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
import ua.faric.aolithium.core.Jk
import ua.faric.aolithium.core.JkAssembler
import ua.faric.aolithium.core.JkDevice
import ua.faric.aolithium.core.JkFrame
import ua.faric.aolithium.core.JkParser
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.tr

/**
 * Зв'язок з платою **JK BMS** (Logic Power у мами faric) по Bluetooth LE: лише **читання**. T5, v0.4.
 *
 * Порядок: підключитись → служба FFE0 → канал FFE1 (сповіщення + запис) → «хто ти» (0x97, кадр 0x03) →
 * «комірки й стан» (0x96). Далі плата сама шле кадри 0x02 по колу; якщо замовкла на [SILENT_MS] — просимо 0x96 знову.
 * Пароль плати для читання не потрібен (у JK він лише для зміни налаштувань — а ми їх не міняємо).
 *
 * Підключення (пошук, повтори, «батарея далеко», остання спроба «чекати батарею», код 133) — та сама схема, що в
 * `JbdLink`, яка пройшла перевірку на A26. Свідомо окремий клас: робочий JbdLink не чіпаємо, доки JK не перевірено
 * біля справжньої батареї; потім — спільна основа (TODO T27).
 */
class JkLink(context: Context, override val address: String, private val quick: Boolean = false) : BatteryLink {
    private val app = context.applicationContext
    private val thread = HandlerThread("jk-link").apply { start() }
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
    private val assembler = JkAssembler()
    private var device: JkDevice? = null
    private var reading = false
    private var askedCells = false
    private var silentRounds = 0
    private var lastPublished = 0L
    private var reconnects = 0
    private var everLive = false
    private var attempt = 0
    private var pendingConnect = false
    private val short get() = (if (quick) tr("фон ", "bg ") else "") + "JK " + BmsMatch.shortAddress(address)
    private val maxReconnects = if (quick) JbdLink.QUICK_RECONNECTS else JbdLink.MAX_RECONNECTS

    private val connectTimeout = Runnable {
        failConnect(tr("Батарея не відповіла за ${JbdLink.CONNECT_TIMEOUT_MS / 1000} с.",
            "The battery did not answer in ${JbdLink.CONNECT_TIMEOUT_MS / 1000} s."))
    }
    private val deviceTimeout = Runnable {
        askCells(tr("на «хто ти» відповіді нема — прошу дані й так", "no answer to \"who are you\", asking for data anyway"))
    }
    private val silence = Runnable { onSilence() }

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

    // ---------- підключення (як JbdLink) ----------

    private fun connect() {
        if (!running) return
        val adapter = BtAccess.adapter(app)
        if (adapter == null || !BtAccess.bluetoothOn(app)) {
            status(LinkStatus.Phase.FAILED, tr(
                "Bluetooth вимкнено. Увімкни його й натисни «Підключити ще раз».",
                "Bluetooth is off. Turn it on and tap \"Reconnect\".",
            ))
            return
        }
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address.uppercase())) {
            running = false
            status(LinkStatus.Phase.FAILED, tr("Неправильна адреса батареї ($address).", "Wrong battery address ($address)."))
            return
        }
        resetSession()
        status(LinkStatus.Phase.CONNECTING, attempt = reconnects)
        val token = ++attempt
        val known = BleScanner.seen[address]
        if (known != null) {
            LinkLog.add(tr("$short: підключаюсь (пристрій з пошуку), спроба ${reconnects + 1}",
                "$short: connecting (device from search), attempt ${reconnects + 1}") +
                (BleScanner.lastRssi[address]?.let { tr(", сигнал ", ", signal ") + Signal.describe(it) } ?: ""))
            h.postDelayed({ connectTo(known, token) }, JbdLink.AFTER_SCAN_MS)
            return
        }
        LinkLog.add(tr("$short: шукаю батарею в ефірі перед підключенням…", "$short: looking for the battery on air before connecting…"))
        main.post {
            BleScanner.findOne(app, address, JbdLink.FIND_MS) { found ->
                h.post {
                    if (!running || token != attempt) return@post
                    val direct = if (found == null && quick) {
                        try {
                            adapter.getRemoteDevice(address.uppercase())
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    } else {
                        null
                    }
                    when {
                        found != null -> {
                            LinkLog.add(tr("$short: знайшлась, підключаюсь", "$short: found, connecting"))
                            h.postDelayed({ connectTo(found, token) }, JbdLink.AFTER_SCAN_MS)
                        }
                        direct != null -> {
                            LinkLog.add(tr("$short: у пошуку не видно — пробую напряму за адресою",
                                "$short: not seen in search, trying directly by address"))
                            h.postDelayed({ connectTo(direct, token) }, JbdLink.AFTER_SCAN_MS)
                        }
                        else -> {
                            LinkLog.add(tr("$short: за ${JbdLink.FIND_MS / 1000} с у пошуку не з'явилась",
                                "$short: did not appear in search in ${JbdLink.FIND_MS / 1000} s"))
                            running = false
                            status(LinkStatus.Phase.FAILED, NOT_ADVERTISING_HINT)
                        }
                    }
                }
            }
        }
    }

    private fun connectTo(device: BluetoothDevice, token: Int) {
        if (!running || token != attempt) return
        val wait = !quick && reconnects >= JbdLink.MAX_RECONNECTS
        if (wait) LinkLog.add(tr("$short: остання спроба — чекаю, доки Android сам підключиться (до ${JbdLink.WAIT_CONNECT_MS / 1000} с)",
            "$short: last attempt, waiting for Android to connect by itself (up to ${JbdLink.WAIT_CONNECT_MS / 1000} s)"))
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
                    status(LinkStatus.Phase.FAILED, tr(
                        "Android не дав дозволу на Bluetooth. Дозволь на головному екрані застосунку.",
                        "Android did not give Bluetooth permission. Allow it on the app's main screen.",
                    ))
                    return@post
                }
                if (!running || token != attempt) {
                    safe { g.close() }
                    return@post
                }
                if (gatt == null) gatt = g
                h.postDelayed(connectTimeout, if (wait) JbdLink.WAIT_CONNECT_MS else JbdLink.CONNECT_TIMEOUT_MS)
            }
        }
    }

    private fun resetSession() {
        assembler.reset()
        reading = false
        askedCells = false
        silentRounds = 0
        writeChar = null
    }

    private fun failConnect(reason: String) {
        LinkLog.add("$short: $reason")
        closeGatt()
        retryOrGiveUp(reason)
    }

    private fun retryOrGiveUp(why: String) {
        if (!running) return
        val reason = BleScanner.lastRssi[address]?.takeIf { Signal.tooFar(it) }
            ?.let {
                tr("$why Батарея далеко (сигнал ${Signal.describe(it)}) — підійди ближче, на 1–3 м.",
                    "$why The battery is far (signal ${Signal.describe(it)}). Move closer, within 1–3 m.")
            } ?: why
        if (reconnects < maxReconnects) {
            reconnects++
            status(LinkStatus.Phase.LOST, tr("$reason Пробую ще раз ($reconnects з $maxReconnects)…",
                "$reason Trying again ($reconnects of $maxReconnects)…"), reconnects)
            h.postDelayed({ connect() }, JbdLink.RECONNECT_DELAY_MS * reconnects)
        } else {
            status(LinkStatus.Phase.FAILED, "$reason $BUSY_HINT")
        }
    }

    private fun closeGatt() {
        h.removeCallbacks(connectTimeout)
        h.removeCallbacks(deviceTimeout)
        h.removeCallbacks(silence)
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
        @Deprecated("Android 13+ кличе версію з value") // uk-only — анотація для компілятора, людина її не бачить
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val copy = c.value?.copyOf() ?: return
            h.post { if (g == gatt) onBytes(copy) }
        }
    }

    private fun onConnectionChanged(g: BluetoothGatt, statusCode: Int, newState: Int) {
        if (gatt == null && pendingConnect) gatt = g
        if (g != gatt) {
            safe { g.close() }
            return
        }
        when {
            newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS -> {
                h.removeCallbacks(connectTimeout)
                LinkLog.add(tr("$short: підключено, шукаю службу FFE0", "$short: connected, looking for service FFE0"))
                status(LinkStatus.Phase.READING)
                h.postDelayed({
                    if (g == gatt && safe { g.discoverServices() } != true) {
                        failConnect(tr("Не вдалось прочитати служби батареї.", "Could not read the battery's services."))
                    }
                }, 600)
            }
            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                LinkLog.add(tr("$short: відключено (код Android $statusCode", "$short: disconnected (Android code $statusCode") +
                    (if (everLive) tr(", після даних)", ", after data)") else ")"))
                closeGatt()
                val reason = if (statusCode == 133 || statusCode == 8 || statusCode == 62) {
                    tr("Android не зміг утримати зв'язок (код $statusCode).", "Android could not keep the connection (code $statusCode).")
                } else {
                    tr("Батарея відключилась.", "The battery disconnected.")
                }
                if (everLive) reconnects = 0
                everLive = false
                retryOrGiveUp(reason)
            }
        }
    }

    private fun onServices(g: BluetoothGatt, statusCode: Int) {
        if (g != gatt) return
        val service = g.getService(UUID.fromString(Jk.SERVICE_UUID))
        if (statusCode != BluetoothGatt.GATT_SUCCESS || service == null) {
            LinkLog.add(tr("$short: служби FFE0 немає (код $statusCode); є: ", "$short: no service FFE0 (code $statusCode); found: ") +
                g.services.joinToString { it.uuid.toString().substring(4, 8) })
            running = false
            closeGatt()
            status(LinkStatus.Phase.FAILED, tr(
                "Це не плата JK BMS: у неї немає потрібної служби Bluetooth (FFE0).",
                "This is not a JK BMS: it lacks the needed Bluetooth service (FFE0).",
            ))
            return
        }
        // Як aiobmsble: серед каналів FFE1 беремо той, що вміє сповіщати, і той, у який можна писати (буває один і той самий).
        val ffe1 = service.characteristics.filter { it.uuid == UUID.fromString(Jk.CHAR_UUID) }
        val notify = ffe1.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
        val write = ffe1.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        if (notify == null || write == null) {
            running = false
            closeGatt()
            status(LinkStatus.Phase.FAILED, tr(
                "У плати немає каналу FFE1 для читання — це не JK BMS або інша версія.",
                "The BMS has no FFE1 channel for reading. It is not JK BMS, or a different version.",
            ))
            return
        }
        writeChar = write
        safe { g.setCharacteristicNotification(notify, true) }
        val cccd = notify.getDescriptor(JbdLink.CCCD)
        if (cccd == null) {
            startReading()
            return
        }
        val ok = safe {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } == true
        if (!ok) startReading()
        h.postDelayed({ if (g == gatt) startReading() }, 1_500)
    }

    // ---------- читання ----------

    private fun startReading() {
        if (!running || reading) return
        reading = true
        LinkLog.add(tr("$short: сповіщення ввімкнено, питаю «хто ти» (0x97)", "$short: notifications on, asking \"who are you\" (0x97)"))
        send(Jk.CMD_DEVICE_INFO)
        h.postDelayed(deviceTimeout, DEVICE_TIMEOUT_MS)
    }

    private fun askCells(why: String) {
        if (!running || gatt == null) return
        h.removeCallbacks(deviceTimeout)
        LinkLog.add("$short: $why")
        askedCells = true
        send(Jk.CMD_CELL_INFO)
        h.removeCallbacks(silence)
        h.postDelayed(silence, SILENT_MS)
    }

    private fun send(cmd: Int) {
        val g = gatt ?: return
        val c = writeChar ?: return
        val bytes = Jk.readCommand(cmd)
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
        LinkLog.add("→ " + LinkLog.hex(bytes) + if (ok) "" else tr("  (Android не прийняв запис)", "  (Android rejected the write)"))
    }

    private fun onBytes(bytes: ByteArray) {
        // Кадр 300 байт — у журнал лише початок шматка, щоб журнал не розпухав.
        LinkLog.add("← " + LinkLog.hex(if (bytes.size > 24) bytes.copyOf(24) else bytes) +
            if (bytes.size > 24) " …(${bytes.size} " + tr("байт", "bytes") + ")" else "")
        val badBefore = assembler.badSums
        for (f in assembler.feed(bytes)) onFrame(f)
        if (assembler.badSums != badBefore) {
            LinkLog.add(tr("   кадр із неправильною сумою — пропущено", "   frame with wrong checksum, skipped"))
        }
    }

    private fun onFrame(f: JkFrame) {
        when (f.type) {
            Jk.TYPE_DEVICE -> {
                device = JkParser.device(f.bytes)
                LinkLog.add(tr("   плата: ${device?.model} · програма ${device?.sw} · залізо ${device?.hw}",
                    "   BMS: ${device?.model} · firmware ${device?.sw} · hardware ${device?.hw}"))
                if (!askedCells) askCells(tr("прошу «комірки й стан» (0x96)", "asking for \"cells and state\" (0x96)"))
            }
            Jk.TYPE_SETTINGS ->
                LinkLog.add(tr("   налаштування плати (лише прочитано, не показуємо)", "   BMS settings (read only, not shown)"))
            Jk.TYPE_CELLS -> onCells(f)
            else -> LinkLog.add(tr("   невідомий кадр 0x%02x", "   unknown frame 0x%02x").format(f.type))
        }
    }

    private fun onCells(f: JkFrame) {
        val c = JkParser.cells(f.bytes, device?.swMajor ?: 0) ?: return
        silentRounds = 0
        h.removeCallbacks(silence)
        h.postDelayed(silence, SILENT_MS)
        val now = System.currentTimeMillis()
        // Плата шле кадри часто — екрану досить раз на [JbdLink.POLL_MS], як і з JBD.
        if (now - lastPublished < JbdLink.POLL_MS - 200 && everLive) return
        lastPublished = now
        if (!everLive) {
            LinkLog.add(tr("   розкладка ${c.layout.title}, комірок ${c.cellsMv.size}", "   layout ${c.layout.title}, cells ${c.cellsMv.size}"))
        }
        everLive = true
        reconnects = 0
        val snap = BatterySnapshot.fromJk(c, device)
        lastSnapshot = snap
        lastSnapshotAt = now
        main.post { listener?.onSnapshot(snap, now) }
        status(LinkStatus.Phase.LIVE)
    }

    /** Кадрів 0x02 нема [SILENT_MS] — просимо ще раз; тричі поспіль — «плата мовчить». */
    private fun onSilence() {
        if (!running || gatt == null) return
        silentRounds++
        val sec = SILENT_MS / 1000
        LinkLog.add(tr("   $sec с без даних (раз $silentRounds)", "   $sec s without data (time $silentRounds)"))
        if (silentRounds >= JbdLink.SILENT_CYCLES_LIMIT) status(LinkStatus.Phase.NO_ANSWER, NO_ANSWER_HINT)
        askCells(tr("прошу «комірки й стан» ще раз", "asking for \"cells and state\" again"))
    }

    private fun status(phase: LinkStatus.Phase, message: String = "", attempt: Int = 0) {
        val s = LinkStatus(phase, message, attempt, if (attempt > 0) maxReconnects else 0)
        if (s == lastStatus) return
        lastStatus = s
        main.post { listener?.onStatus(s) }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        LinkLog.add(tr("$short: Android заборонив (${e.message})", "$short: Android refused (${e.message})"))
        null
    }

    companion object {
        const val DEVICE_TIMEOUT_MS = 3_000L
        const val SILENT_MS = 6_000L

        val NOT_ADVERTISING_HINT: String
            get() = tr(
                "Батарея не з'являється в ефірі. Вона поруч? Застосунок JK BMS / Logic Power на інших " +
                    "телефонах закритий? Поки плата з кимось на зв'язку, її не видно. Закрий інші застосунки й натисни " +
                    "«Підключити ще раз».",
                "The battery does not show up on air. Is it nearby? Is the JK BMS / Logic Power app closed on other " +
                    "phones? While the BMS is connected to someone, it is hidden. Close other apps and tap \"Reconnect\".",
            )
        val BUSY_HINT: String
            get() = tr(
                "Можливо, батарея зараз підключена до іншого телефона чи до застосунку JK BMS — " +
                    "плата тримає лише одне підключення. Закрий той застосунок і натисни «Підключити ще раз».",
                "Maybe the battery is now connected to another phone or to the JK BMS app. " +
                    "The BMS allows only one connection. Close that app and tap \"Reconnect\".",
            )
        val NO_ANSWER_HINT: String
            get() = tr(
                "Підключились, але плата JK не шле даних. Пароль для читання не потрібен — " +
                    "надішли Claude «Журнал зв'язку».",
                "Connected, but the JK BMS sends no data. No password is needed for reading. " +
                    "Send the \"Connection log\" to Claude.",
            )
    }
}

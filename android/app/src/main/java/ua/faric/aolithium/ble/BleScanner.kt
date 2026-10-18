package ua.faric.aolithium.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Signal

/**
 * Пошук пристроїв Bluetooth LE поруч ([SCAN_MS]). Кожен знайдений — з оцінкою, чи це плата батареї.
 * Колбеки — у головному потоці.
 */
class BleScanner(context: Context) {
    /** [rssi] — згладжене число для екрана; [order] — коли з'явився (порядок у списку не міняється). */
    data class Found(val address: String, val name: String?, val rssi: Int, val kind: BmsMatch.Kind, val order: Int)

    interface Listener {
        fun onFound(all: List<Found>)
        fun onFinished(error: String?)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private var listener: Listener? = null
    private var scanning = false

    val isScanning: Boolean get() = scanning
    val results: List<Found> get() = sorted()

    private val endScan = Runnable { finish(null) }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { add(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { add(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { finish(scanError(errorCode)) }
        }
    }

    fun start(l: Listener) {
        listener = l
        if (scanning) return
        found.clear()
        smoothed.clear()
        val scanner = BtAccess.adapter(app)?.bluetoothLeScanner
        if (scanner == null || !BtAccess.bluetoothOn(app)) {
            l.onFinished("Bluetooth вимкнено.")
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            l.onFinished("Android не дав дозволу шукати пристрої.")
            return
        }
        scanning = true
        LinkLog.add("пошук: почато")
        main.postDelayed(endScan, SCAN_MS)
    }

    fun stop() {
        if (scanning) finish(null)
        listener = null
    }

    private fun finish(error: String?) {
        main.removeCallbacks(endScan)
        if (scanning) {
            try {
                BtAccess.adapter(app)?.bluetoothLeScanner?.stopScan(callback)
            } catch (e: SecurityException) {
                // дозвіл забрали посеред пошуку — нічого страшного
            } catch (e: IllegalStateException) {
                // Bluetooth вимкнули посеред пошуку
            }
        }
        scanning = false
        LinkLog.add("пошук: кінець, знайдено ${found.size}" + (error?.let { " ($it)" } ?: ""))
        listener?.onFinished(error)
    }

    private fun add(r: ScanResult) {
        if (!scanning) return
        val record = r.scanRecord
        val name = record?.deviceName ?: try {
            r.device.name
        } catch (e: SecurityException) {
            null
        }
        val services = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val mfr = record?.manufacturerSpecificData
        val ids = if (mfr == null) emptyList() else (0 until mfr.size()).map { mfr.keyAt(it) }
        val address = r.device.address
        seen[address] = r.device
        val kind = BmsMatch.kind(address, name, services, ids)
        val old = found[address]
        val smooth = Signal.smooth(smoothed[address], r.rssi)
        smoothed[address] = smooth
        lastRssi[address] = smooth
        val shown = Signal.shown(old?.rssi, smooth)
        val updated = Found(
            address, name ?: old?.name, shown,
            if (old != null && old.kind != BmsMatch.Kind.OTHER) old.kind else kind,
            old?.order ?: found.size,
        )
        if (updated == old) return // нічого видимого не змінилось — екран не перемальовуємо
        found[address] = updated
        notifySoon()
    }

    private val smoothed = HashMap<String, Int>()
    private var notifyPending = false

    /** Не частіше ніж раз на [REFRESH_MS] — щоб список не мерехтів. */
    private fun notifySoon() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            if (scanning) listener?.onFound(sorted())
        }, REFRESH_MS)
    }

    /**
     * Спершу батареї, потім інші; усередині — у порядку, в якому з'явились, і цей порядок більше не міняється
     * (18.10: сортування за сигналом змушувало рядки стрибати щосекунди).
     */
    private fun sorted(): List<Found> = found.values.sortedWith(
        compareBy<Found> { if (it.kind == BmsMatch.Kind.OTHER) 1 else 0 }.thenBy { it.order },
    )

    private fun scanError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> null
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Android не дав почати пошук (код $code). Вимкни й увімкни Bluetooth і спробуй ще раз."
        else -> "Пошук не вдався (код Android $code)."
    }

    companion object {
        const val SCAN_MS = 15_000L
        const val REFRESH_MS = 700L

        /** Останній (згладжений) сигнал кожного пристрою — щоб екран батареї міг сказати «ти далеко». */
        val lastRssi = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /**
         * Пристрої з останніх пошуків. Підключатись краще до пристрою з пошуку, ніж за голою адресою:
         * Android тоді знає тип адреси й що батарея в ефірі (інакше Samsung часто дає код 133).
         */
        val seen = java.util.concurrent.ConcurrentHashMap<String, BluetoothDevice>()

        /** Коротко пошукати одну адресу. [done] — у головному потоці, null — не знайшлась за [timeoutMs]. */
        fun findOne(context: Context, address: String, timeoutMs: Long, done: (BluetoothDevice?) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            val scanner = BtAccess.adapter(context)?.bluetoothLeScanner
            if (scanner == null) {
                done(null)
                return
            }
            var finished = false
            lateinit var cb: ScanCallback
            fun finish(d: BluetoothDevice?) {
                if (finished) return
                finished = true
                try {
                    scanner.stopScan(cb)
                } catch (e: SecurityException) {
                    // дозвіл забрали — нічого
                } catch (e: IllegalStateException) {
                    // Bluetooth вимкнули
                }
                done(d)
            }
            cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    main.post {
                        if (result.device.address.equals(address, ignoreCase = true)) {
                            seen[result.device.address] = result.device
                            lastRssi[result.device.address] = result.rssi
                            finish(result.device)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    main.post { finish(null) }
                }
            }
            // Неправильна адреса — ScanFilter кидає виняток і закриває застосунок (знайдено на емуляторі 18.10).
            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address.uppercase())) {
                done(null)
                return
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                val filters = listOf(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build())
                scanner.startScan(filters, settings, cb)
            } catch (e: SecurityException) {
                done(null)
                return
            } catch (e: IllegalArgumentException) {
                done(null)
                return
            }
            main.postDelayed({ finish(null) }, timeoutMs)
        }
    }
}

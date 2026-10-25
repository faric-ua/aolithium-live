package ua.faric.aolithium.bg

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import ua.faric.aolithium.R
import ua.faric.aolithium.ble.BatteryLink
import ua.faric.aolithium.ble.BleScanner
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.guard.GuardRunner
import ua.faric.aolithium.data.HistoryStore
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.data.SignalStore
import ua.faric.aolithium.ui.MainActivity
import ua.faric.aolithium.widget.Widgets

/**
 * Запис у фоні (v0.2.5, TODO T3). Поки увімкнено хоч для однієї батареї — висить тихе сповіщення
 * (без нього Android, особливо Samsung, зупиняє фонову роботу за кілька хвилин).
 *
 * Раз на [Background.EVERY_MS]: для кожної увімкненої батареї — коротке підключення (`JbdLink` або `JkLink` у режимі quick, `LinkHolder.create`),
 * один знімок → історія (`HistoryStore`) і «останні дані» (`LastSnapshot`) → відключення. Батарея зайнята
 * лише кілька секунд, тож Aolithium на іншому телефоні теж підключиться.
 *
 * Будить нас будильник Android (`BgReceiver`), бо звичайний таймер спить разом з телефоном.
 * Якщо саме відкритий екран цієї батареї — фон її не чіпає (екран сам пише раз на 10 с), а якщо екран
 * відкрили посеред фонового читання — фон поступається (`yieldTo`).
 * Застосунок і тут лише читає плату — ті самі команди читання, що й екран.
 */
class RecordService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var settings: BgSettings
    private val queue = ArrayDeque<String>()
    private var link: BatteryLink? = null
    private var linkAddress: String? = null
    @Volatile private var busy = false
    private val fallbackTick = Runnable { tick() }
    private val readTimeout = Runnable { finishRead(null, Background.Skip.NO_ANSWER.name) }

    /** T81: коли почали поточне читання й чи дійшли до підключення — щоб назвати конкретну причину невдачі. */
    private var readStartedAt = 0L
    private var readConnected = false

    /** T55: скільки тіків поспіль не дістали батарею (лише в пам'яті). */
    private var failStreak = 0
    private var tickFailed = false

    /**
     * T40: розблокування телефона. «Користувач тут» (`USER_PRESENT`) Android з Android 8 не надсилає приймачам
     * із маніфесту — лише зареєстрованим у живому застосунку. Тож слухає сама служба, поки працює запис у фоні.
     */
    private val unlock = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Widgets.refresh(this@RecordService) // одразу перемалювати «записано … тому» (і сірий, якщо давно)
            if (Background.readOnUnlock(settings.lastTickAt, System.currentTimeMillis(), busy)) {
                LinkLog.add(tr("фон: телефон розблоковано — читаю", "background: phone unlocked — reading"))
                tick()
            }
        }
    }
    private var unlockRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = BgSettings(this)
        instance = this
        GuardRunner.ensure(this) // T48: сторож живе разом із записом у фоні
        try {
            // Системне повідомлення — прапорець EXPORTED / NOT_EXPORTED (Android 13+) для нього не потрібен.
            registerReceiver(unlock, IntentFilter(Intent.ACTION_USER_PRESENT))
            unlockRegistered = true
        } catch (e: RuntimeException) {
            LinkLog.add(tr("фон: не вийшло слухати розблокування", "background: could not listen for unlock"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promote()) {
            stopEverything()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                settings.offAll()
                LinkLog.add(tr("фон: вимкнено зі сповіщення", "background: turned off from the notification"))
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // Увімкнули / вимкнули батарею на екрані: прочитати одразу, якщо давно не читали.
                if (settings.enabled().isEmpty()) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                if (System.currentTimeMillis() - settings.lastTickAt > 60_000L) tick() else updateNote()
            }
            else -> {
                // ACTION_TICK від будильника, перезапуск Android (intent == null), увімкнення телефона.
                if (settings.enabled().isEmpty()) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                val due = System.currentTimeMillis() - settings.lastTickAt >= Background.EVERY_MS - 30_000L
                if (due || intent?.action == ACTION_TICK) tick() else schedule()
            }
        }
        ensureWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (unlockRegistered) {
            unlockRegistered = false
            try {
                unregisterReceiver(unlock)
            } catch (e: IllegalArgumentException) {
                // уже відписано
            }
        }
        h.removeCallbacksAndMessages(null)
        stopWatch()
        link?.let { it.listener = null; it.stop() }
        link = null
        Wake.release()
        super.onDestroy()
    }

    // ---------- сповіщення ----------

    /** Стати «службою на передньому плані». Не вийшло (Android не дозволив) — false. */
    private fun promote(): Boolean {
        val note = buildNote()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTE_ID, note)
            }
            true
        } catch (e: SecurityException) {
            // Android 14+: служба «пристрої поблизу» лише з дозволом на Bluetooth.
            LinkLog.add(tr("фон: Android не дозволив запуск (${e.message})", "background: Android did not allow start (${e.message})"))
            false
        } catch (e: IllegalStateException) {
            // Android 12+: з фону запускати не можна (ForegroundServiceStartNotAllowedException).
            LinkLog.add(tr("фон: Android не дозволив запуск з фону", "background: Android did not allow start from background"))
            false
        }
    }

    private fun buildNote(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) ensureChannel(nm)
        val batteries = Batteries(this)
        val enabled = settings.enabled()
        // T21 (faric 19.10 20:55): час останнього читання — у верхньому рядку сповіщення (праворуч), не в тексті.
        val lines = enabled.map { addr ->
            val name = batteries.find(addr)?.title ?: BmsMatch.shortAddress(addr)
            Background.line(name, settings.result(addr), withTime = false)
        }
        val lastAt = enabled.mapNotNull { settings.result(it)?.at }.maxOrNull()
        val text = lines.joinToString("\n").ifBlank { tr("Запис у фоні вимкнено", "Background recording is off") }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note_battery)
            .setContentTitle(tr("Запис у фоні · раз на 5 хв", "Background recording · every 5 min"))
            .setContentText(lines.firstOrNull() ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(lastAt != null)
            .setWhen(lastAt ?: System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_note_battery), tr("Вимкнути", "Turn off"), stop).build(),
            )
            .build()
    }

    private fun updateNote() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, buildNote())
        } catch (e: SecurityException) {
            // Android 13+: сповіщення заборонені — служба все одно працює
        }
    }

    // ---------- розклад ----------

    private fun schedule() {
        val at = Background.nextAt(settings.lastTickAt, System.currentTimeMillis(), Background.retrySoon(failStreak))
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = tickIntent(this)
        try {
            // До Android 12 точний будильник дозволено без окремого дозволу; з 12 — неточний (Android може
            // зсунути на кілька хвилин, уночі — до ~10 хв). Обидва будять телефон, навіть коли він спить.
            if (Build.VERSION.SDK_INT < 31) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        // Запасний таймер — поки телефон не спить, він точніший за неточний будильник.
        h.removeCallbacks(fallbackTick)
        h.postDelayed(fallbackTick, (at - System.currentTimeMillis()).coerceAtLeast(60_000L))
    }

    // ---------- читання ----------

    private fun tick() {
        if (busy) return
        val list = settings.enabled().filterNot { it.startsWith("demo:") }
        if (list.isEmpty()) {
            stopEverything()
            return
        }
        busy = true
        tickFailed = false
        GuardRunner.ensure(this)
        // T81: слухання ефіру могло тихо зупинитись (Bluetooth вимикали) — раз на пів години перезапустити.
        if (!BtAccess.bluetoothOn(this)) stopWatch()
        else if (System.currentTimeMillis() - watchStartedAt > WATCH_RESTART_MS) stopWatch()
        ensureWatch()
        settings.lastTickAt = System.currentTimeMillis()
        schedule()
        Wake.hold(this, Background.READ_TIMEOUT_MS * list.size + 30_000L)
        queue.clear()
        queue.addAll(list)
        readNext()
    }

    private fun readNext() {
        val addr = queue.removeFirstOrNull()
        if (addr == null) {
            busy = false
            finishedReads++ // T73: /diag бачить, що читання на його запит скінчилось (вдало чи ні)
            // T55: не дістали — наступна спроба раніше (до двох разів поспіль), дістали — знову раз на 5 хв.
            failStreak = if (tickFailed) failStreak + 1 else 0
            if (Background.retrySoon(failStreak)) {
                LinkLog.add(tr("фон: не вийшло — ще раз через 2 хв", "background: failed — again in 2 min"))
            }
            schedule()
            updateNote()
            Wake.release()
            return
        }
        val skip = Background.decide(
            screenHoldsIt = LinkHolder.holds(addr),
            permissionOk = BtAccess.missingPermissions(this).isEmpty(),
            bluetoothOn = BtAccess.bluetoothOn(this),
            locationOn = BtAccess.locationOn(this),
        )
        if (skip != null) {
            val short = BmsMatch.shortAddress(addr)
            LinkLog.add(tr("фон $short: пропускаю — ${skip.text}", "background $short: skipping — ${skip.text}"))
            // T12: у пам'ять — назва причини, не текст: показ перекладає `Background.Skip.localize` (мову можуть перемкнути).
            settings.saveResult(addr, Background.Result(System.currentTimeMillis(), ok = false, note = skip.name))
            // Екран сам пише й сповіщає; а без Bluetooth чи дозволу — даних нема, як і коли батарея далеко.
            if (skip != Background.Skip.SCREEN_OPEN) Notifier.onNoData(this, addr, System.currentTimeMillis())
            if (skip == Background.Skip.LOCATION_OFF) tickFailed = true
            h.post { readNext() }
            return
        }
        val l = LinkHolder.create(this, addr, quick = true)
        link = l
        linkAddress = addr
        readStartedAt = System.currentTimeMillis()
        readConnected = false
        l.listener = object : BatteryLink.Listener {
            override fun onStatus(status: LinkStatus) {
                if (status.phase == LinkStatus.Phase.READING || status.phase == LinkStatus.Phase.LIVE) readConnected = true
                if (status.phase == LinkStatus.Phase.FAILED || status.phase == LinkStatus.Phase.NO_ANSWER) {
                    finishRead(null, Background.Skip.NO_ANSWER.name)
                }
            }

            override fun onSnapshot(snapshot: BatterySnapshot, atMs: Long) {
                finishRead(snapshot, "", atMs)
            }
        }
        h.postDelayed(readTimeout, Background.READ_TIMEOUT_MS)
        l.start()
    }

    /** Знімок є — записати; нема — записати причину ([note] — назва `Background.Skip`). Потім — до наступної батареї. */
    private fun finishRead(s: BatterySnapshot?, note: String, atMs: Long = System.currentTimeMillis()) {
        val l = link ?: return
        val addr = linkAddress ?: return
        h.removeCallbacks(readTimeout)
        l.listener = null
        l.stop()
        link = null
        linkAddress = null
        if (s != null) {
            HistoryStore.get(this).record(addr, s, atMs)
            LastSnapshot(this).save(addr, s, atMs, always = true) // T66
            settings.saveResult(addr, Background.Result(atMs, ok = true, soc = s.soc, current = s.current))
            val short = BmsMatch.shortAddress(addr)
            LinkLog.add(tr("фон $short: записано ${s.soc} %, ${Background.flow(s.current)}",
                "background $short: saved ${s.soc} %, ${Background.flow(s.current)}"))
            Notifier.onSnapshot(this, addr, s, atMs)
            Widgets.onData(this, fromScreen = false)
        } else {
            // T81: замість «нема зв'язку» — чому саме: не видно в ефірі / слабкий сигнал / не підключається / плата мовчить.
            val reason = if (note == Background.Skip.NO_ANSWER.name) {
                val key = addr.uppercase()
                val seenAt = BleScanner.seenAt.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: 0L
                val rssi = BleScanner.lastRssi.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                Background.why(seen = seenAt >= readStartedAt - SEEN_BEFORE_MS, rssi = rssi, connected = readConnected)
            } else note
            settings.saveResult(addr, Background.Result(atMs, ok = false, note = reason))
            if (Background.Skip.isNoLink(reason)) tickFailed = true
            val short = BmsMatch.shortAddress(addr)
            val why = Background.Skip.localize(reason)
            LinkLog.add(tr("фон $short: $why", "background $short: $why"))
            Notifier.onNoData(this, addr, atMs)
            Widgets.onData(this, fromScreen = false)
        }
        updateNote()
        // Пауза між батареями — Android'у треба час закрити попереднє підключення.
        h.postDelayed({ readNext() }, 1_500L)
    }

    /** Екран відкрив цю батарею посеред фонового читання — відпустити її негайно. */
    private fun cancelFor(address: String) {
        val l = link ?: return
        if (!address.equals(linkAddress, ignoreCase = true)) return
        val short = BmsMatch.shortAddress(address)
        LinkLog.add(tr("фон $short: поступаюсь відкритому екрану", "background $short: giving way to the open screen"))
        h.removeCallbacks(readTimeout)
        l.listener = null
        l.stop()
        link = null
        linkAddress = null
        h.postDelayed({ readNext() }, 1_500L)
    }

    // ---------- T81: слухати ефір і ловити момент ----------

    private var watching: Set<String> = emptySet()
    private var watchStartedAt = 0L
    private val lastCatchAt = HashMap<String, Long>()
    private val lastSightLogAt = HashMap<String, Long>()
    private val lastSightLogRssi = HashMap<String, Int>()

    private val watchCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post { onSighting(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            h.post { results.forEach { onSighting(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post {
                watching = emptySet()
                LinkLog.add(tr("ефір: Android не дав слухати (код $errorCode) — спробую на наступному читанні",
                    "air: Android did not let us listen (code $errorCode) — will retry on the next read"))
            }
        }
    }

    /**
     * Тихо слухати ефір лише на свої батареї (фільтр за адресою — Android тоді шукає ощадливо й не вимикає пошук,
     * коли екран погашено). Перезапускаємо лише коли змінився список батарей.
     */
    private fun ensureWatch() {
        val want = settings.enabled().filterNot { it.startsWith("demo:") }
            .map { it.uppercase() }
            .filter { android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it) }
            .toSet()
        if (want == watching) return
        stopWatch()
        if (want.isEmpty()) return
        val ok = BtAccess.missingPermissions(this).isEmpty() && BtAccess.bluetoothOn(this)
        if (!ok) return
        val scanner = BtAccess.adapter(this)?.bluetoothLeScanner ?: return
        val filters = want.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settingsLe = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner.startScan(filters, settingsLe, watchCallback)
            watching = want
            watchStartedAt = System.currentTimeMillis()
            LinkLog.add(tr("ефір: слухаю свої батареї (${want.size}) — щойно з'явиться з добрим сигналом, прочитаю",
                "air: listening for my batteries (${want.size}) — will read as soon as one appears with a good signal"))
            if (!BtAccess.locationOn(this)) {
                LinkLog.add(tr("ефір: геолокація вимкнена — на цьому Android без неї пошук нічого не бачить",
                    "air: location is off — on this Android search sees nothing without it"))
            }
        } catch (e: SecurityException) {
            LinkLog.add(tr("ефір: немає дозволу слухати", "air: no permission to listen"))
        } catch (e: IllegalArgumentException) {
            LinkLog.add(tr("ефір: Android не прийняв фільтр", "air: Android did not accept the filter"))
        } catch (e: IllegalStateException) {
            // Bluetooth вимкнули саме зараз
        }
    }

    private fun stopWatch() {
        if (watching.isEmpty()) return
        watching = emptySet()
        try {
            BtAccess.adapter(this)?.bluetoothLeScanner?.stopScan(watchCallback)
        } catch (e: SecurityException) {
            // дозвіл забрали
        } catch (e: IllegalStateException) {
            // Bluetooth вимкнули
        }
    }

    /** Батарею почули в ефірі: запам'ятати сигнал (для «Моїх батарей»), записати в журнал, а якщо даних давно нема — прочитати. */
    private fun onSighting(r: ScanResult) {
        val device = r.device ?: return
        val addr = device.address ?: return
        val now = System.currentTimeMillis()
        BleScanner.seen[addr] = device
        BleScanner.seenAt[addr] = now
        BleScanner.lastRssi[addr] = r.rssi
        SignalStore(this).save(addr, r.rssi, now)
        val key = addr.uppercase()
        val short = BmsMatch.shortAddress(addr)
        if (Background.logSighting(now, lastSightLogAt[key] ?: 0L, r.rssi, lastSightLogRssi[key])) {
            lastSightLogAt[key] = now
            lastSightLogRssi[key] = r.rssi
            LinkLog.add(tr("ефір $short: бачу батарею, сигнал ${ua.faric.aolithium.core.Signal.describe(r.rssi)}",
                "air $short: I see the battery, signal ${ua.faric.aolithium.core.Signal.describe(r.rssi)}"))
        }
        val enabledAddr = settings.enabled().firstOrNull { it.equals(addr, ignoreCase = true) } ?: return
        if (LinkHolder.holds(enabledAddr)) return
        val last = settings.result(enabledAddr)
        val lastOk = LastSnapshot(this).load(enabledAddr)?.second ?: last?.takeIf { it.ok }?.at
        if (!Background.shouldCatch(now, r.rssi, lastOk, lastCatchAt[key] ?: 0L, busy)) return
        lastCatchAt[key] = now
        val gone = lastOk?.let { " " + tr("(даних нема ${(now - it) / 60_000L} хв)", "(no data for ${(now - it) / 60_000L} min)") } ?: ""
        LinkLog.add(tr("ефір $short: батарея з'явилась з сигналом ${r.rssi} dBm$gone — читаю одразу",
            "air $short: the battery appeared with ${r.rssi} dBm$gone — reading right away"))
        readOnly(enabledAddr)
    }

    /** Прочитати одну батарею поза розкладом (розклад раз на 5 хв не зсуваємо). */
    private fun readOnly(address: String) {
        if (busy) return
        busy = true
        tickFailed = false
        Wake.hold(this, Background.READ_TIMEOUT_MS + 30_000L)
        queue.clear()
        queue.add(address)
        readNext()
    }

    private fun stopEverything() {
        stopWatch()
        h.removeCallbacksAndMessages(null)
        link?.let { it.listener = null; it.stop() }
        link = null
        linkAddress = null
        queue.clear()
        busy = false
        getSystemService(AlarmManager::class.java)?.cancel(tickIntent(this))
        Wake.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL = "background"

        /** Канал тихого сповіщення «Запис у фоні»; повторний виклик (T12, зміна мови) лише перейменовує його. */
        fun ensureChannel(nm: NotificationManager) {
            val ch = NotificationChannel(CHANNEL, tr("Запис у фоні", "Background recording"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Тихе сповіщення, поки Aolithium Live раз на 5 хв записує показники батареї",
                    "Quiet notification while Aolithium Live records the battery every 5 min")
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        const val NOTE_ID = 5

        /** T81: побачили батарею в ефірі незадовго до спроби — теж «видно» (пошук перед підключенням міг її вже не застати). */
        private const val SEEN_BEFORE_MS = 2 * 60_000L
        private const val WATCH_RESTART_MS = 30 * 60_000L
        const val ACTION_TICK = "ua.faric.aolithium.bg.TICK"
        const val ACTION_REFRESH = "ua.faric.aolithium.bg.REFRESH"
        const val ACTION_STOP = "ua.faric.aolithium.bg.STOP"

        @Volatile private var instance: RecordService? = null

        /** Чи працює служба зараз (для «Ще»). */
        val running: Boolean get() = instance != null

        /** Будильник будить не службу, а `BgReceiver`: він тримає телефон не заснути, доки служба почне. */
        fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, BgReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Запустити або оновити службу, якщо фон увімкнено хоч для однієї батареї. Кликати, коли застосунок
         * на екрані (з фону Android 12+ не дозволяє) або з `BgReceiver` (після увімкнення телефона й оновлення).
         */
        fun ensure(ctx: Context, action: String = ACTION_REFRESH): Boolean {
            if (BgSettings(ctx).enabled().isEmpty() && instance == null) return false
            return try {
                ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(action))
                true
            } catch (e: IllegalStateException) {
                val why = e.javaClass.simpleName
                LinkLog.add(tr("фон: Android не дав запустити службу ($why)", "background: Android did not let the service start ($why)"))
                false
            } catch (e: SecurityException) {
                LinkLog.add(tr("фон: Android не дав запустити службу (немає дозволу)",
                    "background: Android did not let the service start (no permission)"))
                false
            }
        }

        /**
         * T64: сторож просить свіже читання («/diag»). Служба працює — читає зараз (якщо вже не читає); не працює — false.
         */
        fun requestRead(): Boolean {
            val s = instance ?: return false
            s.h.post {
                if (!s.busy) {
                    LinkLog.add(tr("фон: сторож просить свіже читання (/diag)", "background: the guard asks for a fresh read (/diag)"))
                    s.tick()
                }
            }
            return true
        }

        /**
         * T73: скільки разів фон закінчив читання (вдало чи ні) з запуску застосунку. /diag запам'ятовує число до
         * запиту — більше стало, отже фон уже спробував і далі чекати нема чого.
         */
        @Volatile var finishedReads = 0L
            private set

        /** T66: фон зараз читає батарею (для /diag — чи вже можна складати файл). */
        val reading: Boolean get() = instance?.busy == true

        /** Кличе `LinkHolder`, коли відкривають екран батареї. Головний потік. */
        fun yieldTo(address: String) {
            instance?.cancelFor(address)
        }
    }
}

/** Не дати телефону заснути, поки йде одне фонове читання (обмежено в часі — навіть якщо щось зависне). */
internal object Wake {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun hold(ctx: Context, ms: Long) {
        val l = lock ?: (ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aolithium:background")
            ?.apply { setReferenceCounted(false) }
            ?.also { lock = it }
            ?: return
        l.acquire(ms)
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }
}

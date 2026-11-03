package ua.faric.aolithium.bg

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ua.faric.aolithium.R
import ua.faric.aolithium.ble.BatteryLink
import ua.faric.aolithium.ble.LinkHolder
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.LinkStatus
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Precise
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.HistoryStore
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.ui.MainActivity
import ua.faric.aolithium.widget.Widgets

/**
 * T91 «Точний запис»: стан у пам'яті (не в налаштуваннях — після закриття чи перезапуску застосунку завжди вимкнено).
 *
 * Поки увімкнено, зв'язок з батареєю ([LinkHolder]) не рветься, коли екран батареї сховали (згорнули застосунок,
 * відкрили інший, перейшли на головну): читання бере [recorder] і пише кожне в історію. Служба [PreciseService]
 * тримає застосунок живим (сповіщення «Точний запис») і стежить за екраном телефона: погас на 5 хв — вимикає.
 * Головний потік.
 */
object PreciseMode {
    /** Остання зупинка — щоб картка могла сказати «зупинився: екран був вимкнений 5 хв». */
    class LastRun(val from: Long, val to: Long, val why: Precise.Stop)

    var address: String? = null
        private set
    var startedAt = 0L
        private set
    var screenOffSince: Long? = null
        private set
    var lastRun: LastRun? = null
        private set
    var points = 0
        private set

    private var app: Context? = null
    private val observers = LinkedHashSet<() -> Unit>()

    fun isOn(addr: String): Boolean = address?.equals(addr, ignoreCase = true) == true

    val on: Boolean get() = address != null

    fun observe(cb: () -> Unit) {
        observers += cb
    }

    fun unobserve(cb: () -> Unit) {
        observers -= cb
    }

    private fun changed() = observers.toList().forEach { it() }

    /** Увімкнути (кнопка на екрані батареї — застосунок на екрані, тож Android дозволить службу). */
    fun start(ctx: Context, addr: String): Boolean {
        if (isOn(addr)) return true
        address?.let { stop(Precise.Stop.OTHER) }
        val c = ctx.applicationContext
        app = c
        address = addr
        startedAt = System.currentTimeMillis()
        screenOffSince = null
        points = 0
        val ok = try {
            c.startForegroundService(Intent(c, PreciseService::class.java))
            true
        } catch (e: IllegalStateException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            LinkLog.add(tr("точний запис: Android не дав запустити", "precise recording: Android did not allow it"))
            stop(Precise.Stop.FAILED)
            return false
        }
        LinkLog.add(tr("точний запис: увімкнено", "precise recording: on"))
        changed()
        return true
    }

    fun stop(why: Precise.Stop) {
        val addr = address ?: return
        address = null
        lastRun = LastRun(startedAt, System.currentTimeMillis(), why)
        screenOffSince = null
        LinkLog.add(tr("точний запис: зупинено (${why.text}), записано $points", "precise recording: stopped (${why.text}), saved $points"))
        // Екран батареї не слухає (застосунок згорнули) — зв'язок більше нікому не потрібен: батарея вільна.
        LinkHolder.onPreciseStopped(addr)
        app?.let { c ->
            try {
                c.stopService(Intent(c, PreciseService::class.java))
            } catch (e: RuntimeException) {
                // служби вже нема
            }
        }
        changed()
    }

    /** Екран телефона погас (null — увімкнули й розблокували). */
    fun screen(offSince: Long?) {
        if (address == null || screenOffSince == offSince) return
        screenOffSince = offSince
        changed()
    }

    /** Перевірка служби: час зупинитись? */
    fun check(now: Long) {
        if (address == null) return
        Precise.stopReason(now, screenOffSince)?.let { stop(it) }
    }

    /** Записали ще одну точку (для лічильника в сповіщенні й журналі). */
    fun counted() {
        points++
    }

    /** Слухач зв'язку, поки екран батареї сховано: пише кожне читання, як і відкритий екран. */
    val recorder = object : BatteryLink.Listener {
        override fun onStatus(status: LinkStatus) = Unit

        override fun onSnapshot(snapshot: BatterySnapshot, atMs: Long) {
            val c = app ?: return
            val addr = address ?: return
            HistoryStore.get(c).record(addr, snapshot, atMs, every = Precise.EVERY_MS)
            counted()
            LastSnapshot(c).save(addr, snapshot, atMs)
            Notifier.onSnapshot(c, addr, snapshot, atMs)
            Widgets.onData(c, fromScreen = true)
        }
    }
}

/**
 * Служба «Точного запису» (T91): тихе сповіщення з кнопкою «Зупинити», не дає телефону заснути, слухає екран.
 * Саме Bluetooth-читання — у [LinkHolder] (той самий зв'язок, що й на екрані батареї).
 */
class PreciseService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock? = null
    private var listening = false
    private var shownOff: Long? = -1L

    private val screenWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (PreciseMode.screenOffSince == null) PreciseMode.screen(System.currentTimeMillis())
                Intent.ACTION_USER_PRESENT -> PreciseMode.screen(null)
            }
            refresh()
        }
    }

    private val check = object : Runnable {
        override fun run() {
            if (!PreciseMode.on) {
                stopSelf()
                return
            }
            // Пропущене повідомлення (буває на деяких телефонах): екран увімкнено й розблоковано — не рахуємо «погас».
            if (PreciseMode.screenOffSince != null && unlocked()) PreciseMode.screen(null)
            PreciseMode.check(System.currentTimeMillis())
            if (!PreciseMode.on) return
            PreciseMode.address?.let { LinkHolder.keepForPrecise(this@PreciseService, it) }
            refresh()
            h.postDelayed(this, Precise.CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
            // Системні повідомлення — прапорець EXPORTED / NOT_EXPORTED (Android 13+) для них не потрібен.
            registerReceiver(screenWatch, f)
            listening = true
        } catch (e: RuntimeException) {
            LinkLog.add(tr("точний запис: не вийшло слухати екран", "precise recording: could not watch the screen"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promote()) {
            stopSelf()
            if (PreciseMode.on) PreciseMode.stop(Precise.Stop.FAILED)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            PreciseMode.stop(Precise.Stop.USER)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PreciseMode.on) {
            // Android перезапустив службу без застосунку — точний запис не відновлюємо (стан лише в пам'яті).
            stopSelf()
            return START_NOT_STICKY
        }
        if (!unlocked() && PreciseMode.screenOffSince == null) PreciseMode.screen(System.currentTimeMillis())
        holdAwake()
        h.removeCallbacks(check)
        h.post(check)
        return START_NOT_STICKY
    }

    /** Застосунок змахнули зі списку недавніх — це «закрив». */
    override fun onTaskRemoved(rootIntent: Intent?) {
        PreciseMode.stop(Precise.Stop.CLOSED)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacks(check)
        if (listening) {
            listening = false
            try {
                unregisterReceiver(screenWatch)
            } catch (e: IllegalArgumentException) {
                // уже відписано
            }
        }
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        // Службу зупинила система (не ми) — вимкнути й стан, щоб кнопка не брехала.
        if (PreciseMode.on) PreciseMode.stop(Precise.Stop.FAILED)
        super.onDestroy()
    }

    private fun unlocked(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return pm.isInteractive && km?.isKeyguardLocked != true
    }

    /** Поки пишемо — процесор не спить (інакше з вимкненим екраном читання раз на 2 с завмирає). Не довше за 5 хв без перевірки. */
    private fun holdAwake() {
        val l = lock ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aolithium:precise")
            ?.apply { setReferenceCounted(false) }
        lock = l
        // Обмежено в часі: навіть якщо щось зависне, телефон не триматиметься без сну. Перевірка раз на 5 с подовжує.
        l?.acquire(Precise.LOCKED_MAX_MS + 60_000L)
    }

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
            LinkLog.add(tr("точний запис: Android не дозволив (${e.message})", "precise recording: Android did not allow it (${e.message})"))
            false
        } catch (e: IllegalStateException) {
            LinkLog.add(tr("точний запис: Android не дозволив запуск з фону", "precise recording: Android did not allow start from background"))
            false
        }
    }

    /** Оновити сповіщення, лише коли змінилось «екран погас» (не щоп'ять секунд). */
    private fun refresh() {
        if (!PreciseMode.on) return
        holdAwake()
        val off = PreciseMode.screenOffSince
        if (off == shownOff) return
        shownOff = off
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, buildNote())
        } catch (e: SecurityException) {
            // Android 13+: сповіщення заборонені — запис усе одно йде
        }
    }

    private fun buildNote(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(RecordService.CHANNEL) == null) RecordService.ensureChannel(nm)
        val addr = PreciseMode.address
        val name = addr?.let { Batteries(this).find(it)?.title ?: BmsMatch.shortAddress(it) } ?: ""
        val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
        val text = Precise.stopsAt(PreciseMode.screenOffSince)?.let {
            tr("Екран вимкнено — зупиниться о ${hm.format(Date(it))}", "Screen off — stops at ${hm.format(Date(it))}")
        } ?: tr("Кожне читання — в історію. Вимкнеться, коли закриєш застосунок або екран погасне на 5 хв",
            "Every reading goes to history. Stops when you close the app or the screen is off for 5 min")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, PreciseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, RecordService.CHANNEL)
            .setSmallIcon(R.drawable.ic_note_battery)
            .setContentTitle(tr("Точний запис", "Precise recording") + if (name.isNotBlank()) " · $name" else "")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(PreciseMode.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_note_battery), tr("Зупинити", "Stop"), stop).build(),
            )
            .build()
    }

    companion object {
        const val NOTE_ID = 7
        const val ACTION_STOP = "ua.faric.aolithium.PRECISE_STOP"
    }
}

package ua.faric.aolithium.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import ua.faric.aolithium.bg.PreciseMode
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.core.Precise
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.data.Batteries

/**
 * Тримає одне підключення, поки відкрито екран батареї.
 *
 * Поворот екрана перестворює вікно — без цього кожен поворот рвав би зв'язок і підключався заново.
 * Тому після [release] підключення живе ще [GRACE_MS]: повернувся той самий екран — працюємо далі;
 * ні (застосунок сховали) — відключаємось, щоб батарея була вільна для інших телефонів.
 *
 * T91: увімкнено «Точний запис» для цієї батареї — після [release] не відключаємось, читання бере
 * [PreciseMode.recorder] (пише в історію, поки застосунок згорнутий). Вимкнули — [onPreciseStopped].
 */
object LinkHolder {
    const val GRACE_MS = 5_000L
    private val h = Handler(Looper.getMainLooper())
    private var link: BatteryLink? = null
    private val stopLater = Runnable { stopNow() }

    fun acquire(ctx: Context, address: String): BatteryLink {
        h.removeCallbacks(stopLater)
        link?.let { if (it.address == address && it.lastStatus.phase != LinkStatus.Phase.FAILED) return it }
        // Плата одна, зв'язок один: відкрили іншу батарею — точний запис попередньої зупиняється.
        if (PreciseMode.on && !PreciseMode.isOn(address)) PreciseMode.stop(Precise.Stop.OTHER)
        stopNow()
        // Плата тримає лише одне підключення: якщо саме зараз її читає запис у фоні — хай поступиться екрану.
        RecordService.yieldTo(address)
        val created = DemoLink.sceneOf(address)?.let { DemoLink(it) } ?: create(ctx, address, quick = false)
        link = created
        created.start()
        return created
    }

    /** Зв'язок потрібного типу (v0.4): JK BMS — `JkLink`, решта — `JbdLink`. Один вибір для екрана й фону. */
    fun create(ctx: Context, address: String, quick: Boolean): BatteryLink =
        if (Batteries.kindOf(ctx, address) == BmsMatch.Kind.JK) JkLink(ctx, address, quick) else JbdLink(ctx, address, quick)

    /** «Підключити ще раз»: закрити старе й почати нове. */
    fun restart(ctx: Context, address: String): BatteryLink {
        stopNow()
        return acquire(ctx, address)
    }

    fun release(listener: BatteryLink.Listener) {
        val l = link
        if (l != null && PreciseMode.isOn(l.address)) {
            // T91: застосунок сховали, а точний запис іде — зв'язок лишається, читання пише служба.
            if (l.listener === listener || l.listener == null) l.listener = PreciseMode.recorder
            h.removeCallbacks(stopLater)
            return
        }
        l?.let { if (it.listener === listener) it.listener = null }
        h.removeCallbacks(stopLater)
        h.postDelayed(stopLater, GRACE_MS)
    }

    /** T91: точний запис вимкнено. Екран батареї не слухає (застосунок згорнутий) — відключаємось, батарея вільна. */
    fun onPreciseStopped(address: String) {
        val l = link ?: return
        if (!l.address.equals(address, ignoreCase = true)) return
        if (l.listener === PreciseMode.recorder || l.listener == null) {
            l.listener = null
            h.removeCallbacks(stopLater)
            h.postDelayed(stopLater, GRACE_MS)
        }
    }

    private var revivedAt = 0L

    /**
     * T91: застосунок згорнутий, точний запис іде, а зв'язку нема (обірвався й здався) — підключитись знову, не частіше
     * ніж раз на 30 с. Екран батареї відкритий — не чіпаємо: там свій статус і кнопка «Підключити ще раз».
     */
    fun keepForPrecise(ctx: Context, address: String) {
        val l = link
        val lost = l == null || (l.listener === PreciseMode.recorder && l.lastStatus.phase == LinkStatus.Phase.FAILED)
        if (!lost) return
        val now = System.currentTimeMillis()
        if (now - revivedAt < 30_000L) return
        revivedAt = now
        LinkLog.add(tr("точний запис: підключаюсь знову", "precise recording: reconnecting"))
        stopNow()
        RecordService.yieldTo(address)
        val created = create(ctx, address, quick = false)
        created.listener = PreciseMode.recorder
        link = created
        created.start()
    }

    /** Екран батареї тримає (або щойно тримав, 5 с) зв'язок з цією батареєю — запис у фоні її не чіпає. */
    fun holds(address: String): Boolean =
        link?.let { it.address.equals(address, ignoreCase = true) && it.lastStatus.phase != LinkStatus.Phase.FAILED } == true

    /** T82: екран батареї тримає якийсь зв'язок — запис у фоні не слухає ефір, щоб не заважати підключенню. */
    val active: Boolean get() = link?.let { it.lastStatus.phase != LinkStatus.Phase.FAILED && it !is DemoLink } == true

    fun stopNow() {
        h.removeCallbacks(stopLater)
        link?.let {
            it.listener = null
            it.stop()
        }
        link = null
    }
}

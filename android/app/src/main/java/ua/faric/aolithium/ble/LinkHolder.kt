package ua.faric.aolithium.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import ua.faric.aolithium.bg.RecordService

/**
 * Тримає одне підключення, поки відкрито екран батареї.
 *
 * Поворот екрана перестворює вікно — без цього кожен поворот рвав би зв'язок і підключався заново.
 * Тому після [release] підключення живе ще [GRACE_MS]: повернувся той самий екран — працюємо далі;
 * ні (застосунок сховали) — відключаємось, щоб батарея була вільна для інших телефонів.
 */
object LinkHolder {
    const val GRACE_MS = 5_000L
    private val h = Handler(Looper.getMainLooper())
    private var link: BatteryLink? = null
    private val stopLater = Runnable { stopNow() }

    fun acquire(ctx: Context, address: String): BatteryLink {
        h.removeCallbacks(stopLater)
        link?.let { if (it.address == address && it.lastStatus.phase != LinkStatus.Phase.FAILED) return it }
        stopNow()
        // Плата тримає лише одне підключення: якщо саме зараз її читає запис у фоні — хай поступиться екрану.
        RecordService.yieldTo(address)
        val created = DemoLink.sceneOf(address)?.let { DemoLink(it) } ?: JbdLink(ctx, address)
        link = created
        created.start()
        return created
    }

    /** «Підключити ще раз»: закрити старе й почати нове. */
    fun restart(ctx: Context, address: String): BatteryLink {
        stopNow()
        return acquire(ctx, address)
    }

    fun release(listener: BatteryLink.Listener) {
        link?.let { if (it.listener === listener) it.listener = null }
        h.removeCallbacks(stopLater)
        h.postDelayed(stopLater, GRACE_MS)
    }

    /** Екран батареї тримає (або щойно тримав, 5 с) зв'язок з цією батареєю — запис у фоні її не чіпає. */
    fun holds(address: String): Boolean =
        link?.let { it.address.equals(address, ignoreCase = true) && it.lastStatus.phase != LinkStatus.Phase.FAILED } == true

    fun stopNow() {
        h.removeCallbacks(stopLater)
        link?.let {
            it.listener = null
            it.stop()
        }
        link = null
    }
}

package ua.faric.aolithium.ble

import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.tr

/**
 * Зв'язок з однією батареєю: підключитись, читати по колу, віддавати знімки стану.
 * Зараз — плата JBD (`JbdLink`) і демо (`DemoLink`); пізніше — JK BMS тим самим інтерфейсом.
 * Колбеки [Listener] приходять у головному потоці.
 */
interface BatteryLink {
    val address: String
    var listener: Listener?

    /** Останнє, що відомо (щоб після повороту екрана одразу показати числа). */
    val lastStatus: LinkStatus
    val lastSnapshot: BatterySnapshot?
    val lastSnapshotAt: Long

    fun start()
    fun stop()

    interface Listener {
        fun onStatus(status: LinkStatus)
        fun onSnapshot(snapshot: BatterySnapshot, atMs: Long)
    }
}

/**
 * Стан зв'язку. [message] — пояснення людською мовою (для помилок — що робити).
 * [attempt] / [ofAttempts] — котрий повтор з усіх (T25: «повтор 2 з 3» в одному рядку, без розбору тексту).
 */
data class LinkStatus(val phase: Phase, val message: String = "", val attempt: Int = 0, val ofAttempts: Int = 0) {
    enum class Phase {
        IDLE,
        CONNECTING,
        READING,
        LIVE,
        NO_ANSWER,
        LOST,
        FAILED,
        ;

        val title: String
            get() = when (this) {
                IDLE -> tr("Не підключено", "Not connected")
                CONNECTING -> tr("Підключаюсь…", "Connecting…")
                READING -> tr("Читаю плату…", "Reading BMS…")
                LIVE -> tr("На зв'язку", "Connected")
                NO_ANSWER -> tr("Плата мовчить", "BMS is silent")
                LOST -> tr("Зв'язок втрачено", "Connection lost")
                FAILED -> tr("Не вдалось", "Failed")
            }
    }

    companion object {
        val IDLE = LinkStatus(Phase.IDLE)
    }
}

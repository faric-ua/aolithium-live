package ua.faric.aolithium.ble

import ua.faric.aolithium.core.BatterySnapshot

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

/** Стан зв'язку. [message] — пояснення людською мовою (для помилок — що робити). */
data class LinkStatus(val phase: Phase, val message: String = "") {
    enum class Phase(val title: String) {
        IDLE("Не підключено"),
        CONNECTING("Підключаюсь…"),
        READING("Читаю плату…"),
        LIVE("На зв'язку"),
        NO_ANSWER("Плата мовчить"),
        LOST("Зв'язок втрачено"),
        FAILED("Не вдалось"),
    }

    companion object {
        val IDLE = LinkStatus(Phase.IDLE)
    }
}

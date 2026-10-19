package ua.faric.aolithium.ble

import android.os.Handler
import android.os.Looper
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.DemoFrames
import ua.faric.aolithium.core.JbdAssembler
import ua.faric.aolithium.core.JbdBasic
import ua.faric.aolithium.core.JbdParser
import ua.faric.aolithium.core.Jk
import ua.faric.aolithium.core.JkAssembler
import ua.faric.aolithium.core.JkCells
import ua.faric.aolithium.core.JkDemo
import ua.faric.aolithium.core.JkDevice
import ua.faric.aolithium.core.JkParser

/**
 * Демо без батареї: ті самі байти, що шле плата JBD, проходять той самий розбір, що й справжні.
 * Для знімків екранів у CI і щоб показати екран, коли батареї поруч нема.
 */
class DemoLink(private val scene: DemoFrames.Scene) : BatteryLink {
    override val address: String = ADDRESS_PREFIX + scene.name
    override var listener: BatteryLink.Listener? = null
    override var lastStatus: LinkStatus = LinkStatus.IDLE
        private set
    override var lastSnapshot: BatterySnapshot? = null
        private set
    override var lastSnapshotAt: Long = 0
        private set

    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            publish()
            h.postDelayed(this, JbdLink.POLL_MS)
        }
    }

    override fun start() {
        if (running) return
        running = true
        status(LinkStatus(LinkStatus.Phase.CONNECTING))
        h.postDelayed(tick, 700)
    }

    override fun stop() {
        running = false
        h.removeCallbacksAndMessages(null)
        status(LinkStatus.IDLE)
    }

    private fun publish() {
        if (scene == DemoFrames.Scene.JK) return publishJk()
        val a = JbdAssembler()
        var basic: JbdBasic? = null
        var cells: List<Int> = emptyList()
        var model: String? = null
        var counts: List<Pair<String, Int>>? = null
        val bytes = DemoFrames.basic(scene) + DemoFrames.cells(scene) + DemoFrames.hardware() + DemoFrames.errorCounts()
        // Шматками по 20 байт — як сповіщення Bluetooth.
        for (chunk in bytes.toList().chunked(20)) {
            for (f in a.feed(chunk.toByteArray())) when (f.cmd) {
                0x03 -> basic = JbdParser.basic(f.data)
                0x04 -> cells = JbdParser.cells(f.data)
                0x05 -> model = JbdParser.hardware(f.data)
                0xAA -> counts = JbdParser.errorCounts(f.data)
            }
        }
        val b = basic ?: return
        val snap = BatterySnapshot.fromJbd(b, cells, model, counts)
        val now = System.currentTimeMillis()
        lastSnapshot = snap
        lastSnapshotAt = now
        listener?.onSnapshot(snap, now)
        status(LinkStatus(LinkStatus.Phase.LIVE, "Демо: «${scene.title}». Справжньої батареї тут нема."))
    }

    /** JK: «хто ти» + «комірки» шматками по 20 байт → той самий `JkAssembler` / `JkParser`, що в `JkLink`. */
    private fun publishJk() {
        val a = JkAssembler()
        var device: JkDevice? = null
        var cells: JkCells? = null
        for (chunk in (JkDemo.device() + JkDemo.cells()).toList().chunked(20)) {
            for (f in a.feed(chunk.toByteArray())) when (f.type) {
                Jk.TYPE_DEVICE -> device = JkParser.device(f.bytes)
                Jk.TYPE_CELLS -> cells = JkParser.cells(f.bytes, device?.swMajor ?: 0)
            }
        }
        val c = cells ?: return
        val snap = BatterySnapshot.fromJk(c, device)
        val now = System.currentTimeMillis()
        lastSnapshot = snap
        lastSnapshotAt = now
        listener?.onSnapshot(snap, now)
        status(LinkStatus(LinkStatus.Phase.LIVE, "Демо: «${scene.title}». Справжньої батареї тут нема."))
    }

    private fun status(s: LinkStatus) {
        if (s == lastStatus) return
        lastStatus = s
        listener?.onStatus(s)
    }

    companion object {
        const val ADDRESS_PREFIX = "demo:"

        /** Приклад довгого пояснення для знімка стану «Не вдалось» (T25). */
        const val FAILED_SAMPLE = "Не вдалось підключитись (код 133). Батарея далеко (сигнал -90 dBm, слабкий) — підійди ближче, на 1–3 м."

        fun sceneOf(address: String): DemoFrames.Scene? =
            if (!address.startsWith(ADDRESS_PREFIX)) null
            else DemoFrames.Scene.entries.firstOrNull { it.name == address.removePrefix(ADDRESS_PREFIX) }
    }
}

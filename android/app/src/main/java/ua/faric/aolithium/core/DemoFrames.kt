package ua.faric.aolithium.core

/**
 * Готові відповіді плати для демо-режиму (знімки екранів у CI, перевірка без батареї).
 * Числа «спокій» — ті самі, що Aolithium 1.3 показав на відео faric 16.10:
 * 13,31 В · 0,00 А · 93 % · 86,31 з 92,34 А·год · 123 цикли · 12,3 °C.
 * Напруги окремих комірок Aolithium не показує — тут вигадані близькі (середня 3,328 В, як на відео).
 */
object DemoFrames {
    enum class Scene(val title: String) {
        IDLE("Спокій (як на відео)"),
        CHARGING("Заряджається, балансування"),
        DISCHARGING("Світла нема — віддає"),
        COLD("Захист: холодно для заряду"),
        /** v0.4: плата JK BMS (як у Logic Power у мами) — кадри JK з `JkDemo`, не JBD. */
        JK("Logic Power (JK BMS): заряд, балансир"),
    }

    fun basic(scene: Scene): ByteArray = Jbd.responseFrame(Jbd.CMD_BASIC, basicData(scene))

    fun cells(scene: Scene): ByteArray = Jbd.responseFrame(Jbd.CMD_CELLS, words(cellsMv(scene)))

    fun hardware(): ByteArray = Jbd.responseFrame(Jbd.CMD_HARDWARE, "DEMO-JBD-4S-100A".toByteArray(Charsets.US_ASCII))

    fun errorCounts(): ByteArray = Jbd.responseFrame(Jbd.CMD_ERROR_COUNTS, words(listOf(0, 0, 1, 0, 0, 0, 3, 0, 0, 0, 0)))

    fun cellsMv(scene: Scene): List<Int> = when (scene) {
        Scene.IDLE -> listOf(3326, 3329, 3328, 3329)
        Scene.CHARGING -> listOf(3402, 3451, 3418, 3409)
        Scene.DISCHARGING -> listOf(3302, 3305, 3303, 3301)
        Scene.COLD -> listOf(3301, 3305, 3302, 3304)
        Scene.JK -> JkDemo.CELLS_MV
    }

    private fun basicData(scene: Scene): ByteArray {
        val (volts, amps, remain, soc, temp) = when (scene) {
            Scene.IDLE -> listOf(1331, 0, 8631, 93, 2731 + 123)
            Scene.CHARGING -> listOf(1368, 850, 7020, 76, 2731 + 185)
            Scene.DISCHARGING -> listOf(1321, -410, 5890, 64, 2731 + 163)
            Scene.COLD -> listOf(1321, 0, 5120, 55, 2731 - 42)
            Scene.JK -> listOf(1359, 1864, 23810, 78, 2731 + 196) // не використовується: JK-демо шле кадри JK
        }
        val balance = if (scene == Scene.CHARGING) 0b0010 else 0
        val protection = if (scene == Scene.COLD) 1 shl Protection.CHARGE_COLD.bit else 0
        val mos = if (scene == Scene.COLD) 0x02 else 0x03
        val made = (21 shl 9) or (3 shl 5) or 15 // 15.03.2021 — вигадана дата для демо
        val head = words(listOf(volts, amps and 0xFFFF, remain, 9234, 123, made, balance, 0, protection))
        return head + byteArrayOf(0x10, soc.toByte(), mos.toByte(), 4, 1) + words(listOf(temp))
    }

    private fun words(values: List<Int>): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v shr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }
}

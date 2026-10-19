package ua.faric.aolithium.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Вигадана історія за тиждень для демо (знімки екранів у CI, «Довідка → Демо»): вдень сонце/мережа
 * заряджають, увечері й уночі батарея віддає. Справжня історія демо-батареї не пишеться.
 */
object DemoHistory {
    fun week(now: Long): List<HistoryPoint> {
        val out = ArrayList<HistoryPoint>()
        val step = 60_000L
        var soc = 70.0
        var t = now - 7 * 86_400_000L
        while (t <= now) {
            val hour = ((t / 3_600_000L) % 24).toInt()
            val current = when (hour) {
                in 9..15 -> 9.0 + 3 * sin((t / 600_000.0))
                in 18..23, in 0..6 -> -4.5 - 1.5 * sin((t / 900_000.0))
                else -> 0.0
            }
            soc = (soc + current * (step / 3_600_000.0) / 92.34 * 100).coerceIn(12.0, 100.0)
            val s = soc.toInt()
            // кінець заряду — напруга вгору, як у справжньої батареї (для карток «зарядний» і «хто відстає»)
            val v = 12.9 + s / 100.0 * 0.5 + current * 0.012 + if (s >= 95 && current > 0) 0.6 else 0.0
            val base = (v / 4 * 1000).toInt()
            val spread = if (current > 0 && s > 90) 28 else 6
            val temp = 14 + 3 * sin(2 * PI * hour / 24) + max(0.0, current) * 0.25
            out += HistoryPoint(t, s, v, current, 92.34 * s / 100, temp - 0.8, temp, listOf(base - spread / 2, base + spread / 2, base, base + 1))
            t += step
        }
        return out
    }
}

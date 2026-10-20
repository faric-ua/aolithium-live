package ua.faric.aolithium.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Вигадана історія за тиждень для демо (знімки екранів у CI, «Довідка → Демо»): вдень сонце/мережа
 * заряджають, увечері й уночі батарея віддає. Справжня історія демо-батареї не пишеться.
 *
 * T29 (20.10): у демо «Logic Power» була історія Aolithium (92 А·год, різниця комірок 6 мВ), а живі комірки JK
 * розходяться на 41 мВ — графіки й картки суперечили екрану «Комірки». Тепер у кожної демо-батареї своя історія
 * ([Profile]): ємність, струми й розкид комірок — як у її живих демо-кадрів.
 */
object DemoHistory {
    /**
     * Звідки береться вигадана історія.
     * [spreadIdleMv] — розкид комірок, коли батарея віддає чи стоїть; [spreadTopMv] — наприкінці заряду (> 90 %),
     * коли одна комірка відстає (як у живих кадрах). [lowCell] / [highCell] — яка комірка найнижча / найвища
     * (з 0), як у живих демо-кадрах цієї плати.
     */
    data class Profile(
        val fullAh: Double,
        val chargeA: Double,
        val dischargeA: Double,
        val spreadIdleMv: Int,
        val spreadTopMv: Int,
        val startSoc: Double,
        val lowCell: Int,
        val highCell: Int,
    )

    /** Aolithium 100 А·год (плата знає 92,34) — як було до T29. */
    val AOLITHIUM = Profile(fullAh = 92.34, chargeA = 9.0, dischargeA = 4.5, spreadIdleMv = 6, spreadTopMv = 28, startSoc = 70.0,
        lowCell = 0, highCell = 1)

    /** Logic Power ~305 А·год (JK): струми втричі більші, наприкінці заряду №3 відстає ~41 мВ (як `JkDemo.CELLS_MV`). */
    val LOGIC_POWER = Profile(fullAh = 305.0, chargeA = 28.0, dischargeA = 14.0, spreadIdleMv = 9, spreadTopMv = 41, startSoc = 70.0,
        lowCell = 2, highCell = 0)

    fun week(now: Long, p: Profile = AOLITHIUM): List<HistoryPoint> {
        val out = ArrayList<HistoryPoint>()
        val step = 60_000L
        var soc = p.startSoc
        var t = now - 7 * 86_400_000L
        while (t <= now) {
            val hour = ((t / 3_600_000L) % 24).toInt()
            val current = when (hour) {
                in 9..15 -> p.chargeA * (1 + sin((t / 600_000.0)) / 3)
                in 18..23, in 0..6 -> -p.dischargeA * (1 + sin((t / 900_000.0)) / 3)
                else -> 0.0
            }
            soc = (soc + current * (step / 3_600_000.0) / p.fullAh * 100).coerceIn(12.0, 100.0)
            val s = soc.toInt()
            // струм, перерахований на ємність Aolithium (92 А·год): тоді стрибок напруги від струму схожий для обох батарей
            val perUnit = current * 92.34 / p.fullAh
            // кінець заряду — напруга вгору, як у справжньої батареї (для карток «зарядний» і «хто відстає»)
            val v = 12.9 + s / 100.0 * 0.5 + perUnit * 0.012 + if (s >= 95 && current > 0) 0.6 else 0.0
            val base = (v / 4 * 1000).toInt()
            val spread = if (current > 0 && s > 90) p.spreadTopMv else p.spreadIdleMv
            val temp = 14 + 3 * sin(2 * PI * hour / 24) + max(0.0, perUnit) * 0.25
            out += HistoryPoint(t, s, v, current, p.fullAh * s / 100, temp - 0.8, temp, cells(p, base, spread))
            t += step
        }
        return out
    }

    /** 4 комірки: [Profile.lowCell] нижче на пів розкиду, [Profile.highCell] вище, решта — посередині (+0 / +1 мВ). */
    fun cells(p: Profile, base: Int, spread: Int): List<Int> {
        var mid = 0
        return (0 until 4).map { i ->
            when (i) {
                p.lowCell -> base - spread / 2
                // від найнижчої рівно на розкид: «base + spread / 2» при непарних 41 мВ давало 40 (тест)
                p.highCell -> base - spread / 2 + spread
                else -> base + mid++
            }
        }
    }

    /** Чия історія для демо-адреси: «demo:JK» — Logic Power, решта сцен — Aolithium. */
    fun profileFor(scene: String): Profile = if (scene == "JK") LOGIC_POWER else AOLITHIUM
}

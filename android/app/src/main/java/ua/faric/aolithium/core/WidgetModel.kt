package ua.faric.aolithium.core

import java.util.TimeZone
import kotlin.math.abs

/**
 * Віджет на головний екран (v0.3.1, TODO T4). Прев'ю — «Aolithium Live — прев'ю комірок», екран 9.
 * faric 19.10 20:56: «йдемо далі» (розмір не вибрав) → обидва: великий 4 × 2 і малий 2 × 2.
 *
 * Тут лише що написати й яким кольором (без Android, тести `WidgetModelTest`). Малює — `widget/WidgetArt.kt`.
 * Віджет показує останній збережений знімок (з фону або з відкритого екрана). Давніший за [STALE_MS] —
 * сірий, з часом: телефон, мабуть, далеко від батареї. Час — лише годинник «записано 19:40», без «3 хв тому»:
 * віджет оновлюється не щохвилини, і «3 хв тому» брехало б.
 */
object WidgetModel {
    /** Фон пише раз на ~5 хв (уночі буває до 10): давніше 15 хв — «нема даних». */
    const val STALE_MS = 15 * 60_000L

    enum class Tone { DISCHARGE, CHARGE, IDLE, STALE, EMPTY }
    enum class Arrow { UP, DOWN, NONE }

    data class Model(
        val name: String,
        val tone: Tone,
        /** «64 %» або «—». */
        val percent: String,
        /** Заповнення кільця 0…1. */
        val ring: Float,
        /** Під відсотком у кільці: «58,9 А·год» / «о 18:10». */
        val ringNote: String,
        /** Велике число: «320 Вт» / «стоїть» / «нема даних». */
        val power: String,
        val arrow: Arrow,
        /** «віддає — світла нема» / «заряджається» / «2 год 05 хв — телефон далеко?». */
        val status: String,
        val line1: String,
        val line2: String,
        /** Малий віджет — один рядок під числом. */
        val small: String,
    )

    fun of(name: String, s: BatterySnapshot?, at: Long?, now: Long, tz: TimeZone = TimeZone.getDefault()): Model {
        if (s == null || at == null) {
            return Model(
                name, Tone.EMPTY, "—", 0f, "", "нема даних", Arrow.NONE,
                "ще не було зв'язку", "відкрий застосунок біля батареї", "торкнись — відкрити батарею", "відкрий біля батареї",
            )
        }
        val pct = "${s.soc} %"
        val ring = (s.soc.coerceIn(0, 100) / 100f)
        val time = Background.hhmm(at, tz)
        if (now - at > STALE_MS) {
            return Model(
                name, Tone.STALE, pct, ring, "о $time", "нема даних", Arrow.NONE,
                "${Fmt.duration((now - at) / 3_600_000.0)} — телефон далеко?",
                "показано останнє, що було о $time", "торкнись — відкрити батарею", "нема даних з $time",
            )
        }
        val ah = Fmt.num(s.remainAh, 1) + " А·год"
        return when (s.flow) {
            BatterySnapshot.Flow.DISCHARGING -> {
                val d = TimeLeft.discharge(s.remainAh, s.fullAh, s.current)
                val to20 = d?.toThreshold?.firstOrNull { it.first == 20 }?.second
                val left = when {
                    to20 != null -> "вистачить ≈ ${Fmt.duration(to20)} до 20 %"
                    d != null -> "до кінця ≈ ${Fmt.duration(d.totalH)} — час заряджати"
                    else -> ""
                }
                val short = to20?.let { "≈ ${Fmt.duration(it)}" } ?: d?.let { "≈ ${Fmt.duration(it.totalH)}" } ?: "віддає"
                Model(
                    name, Tone.DISCHARGE, pct, ring, ah, Fmt.watts(abs(s.power)), Arrow.UP,
                    "віддає — світла нема", left, "записано $time", "$short · $time",
                )
            }
            BatterySnapshot.Flow.CHARGING -> {
                val c = TimeLeft.charge(s.remainAh, s.fullAh, s.current)
                val line = c?.let { "до повної ≈ ${Fmt.duration(it.toFullH)} · +${Fmt.num(it.percentPerHour, 0)} % за год" } ?: ""
                Model(
                    name, Tone.CHARGE, pct, ring, ah, Fmt.watts(abs(s.power)), Arrow.DOWN,
                    "заряджається", line, "записано $time", "заряд · $time",
                )
            }
            BatterySnapshot.Flow.IDLE -> {
                val full = s.soc >= 99
                Model(
                    name, Tone.IDLE, pct, ring, ah, "стоїть", Arrow.NONE,
                    if (full) "повна — ні заряду, ні розряду" else "ні заряду, ні розряду",
                    "напруга ${Fmt.volts(s.voltage)}", "записано $time", "${if (full) "повна" else "стоїть"} · $time",
                )
            }
        }
    }
}

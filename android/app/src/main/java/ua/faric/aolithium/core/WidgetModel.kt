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

    fun of(
        name: String, s: BatterySnapshot?, at: Long?, now: Long, tz: TimeZone = TimeZone.getDefault(),
        outageA: Double = Outages.START_A,
    ): Model {
        if (s == null || at == null) {
            return Model(
                name, Tone.EMPTY, "—", 0f, "", tr("нема даних", "no data"), Arrow.NONE,
                tr("ще не було зв'язку", "no connection yet"),
                tr("відкрий застосунок біля батареї", "open the app near the battery"),
                tr("торкнись — відкрити батарею", "tap to open the battery"),
                tr("відкрий біля батареї", "open near the battery"),
            )
        }
        val pct = "${s.soc} %"
        val ring = (s.soc.coerceIn(0, 100) / 100f)
        val time = Background.hhmm(at, tz)
        if (now - at > STALE_MS) {
            val ago = Fmt.duration((now - at) / 3_600_000.0)
            return Model(
                name, Tone.STALE, pct, ring, tr("о $time", "at $time"), tr("нема даних", "no data"), Arrow.NONE,
                tr("$ago — телефон далеко?", "$ago — phone far away?"),
                tr("показано останнє, що було о $time", "showing the last data from $time"),
                tr("торкнись — відкрити батарею", "tap to open the battery"),
                tr("нема даних з $time", "no data since $time"),
            )
        }
        val ah = Fmt.num(s.remainAh, 1) + " " + Fmt.AH
        val saved = tr("записано $time", "saved at $time")
        return when (s.flow) {
            BatterySnapshot.Flow.DISCHARGING -> {
                val d = TimeLeft.discharge(s.remainAh, s.fullAh, s.current)
                val to20 = d?.toThreshold?.firstOrNull { it.first == 20 }?.second
                val left = when {
                    to20 != null -> tr("вистачить ≈ ${Fmt.duration(to20)} до 20 %", "lasts ≈ ${Fmt.duration(to20)} to 20 %")
                    d != null -> {
                        val total = Fmt.duration(d.totalH)
                        tr("до кінця ≈ $total — час заряджати", "until empty ≈ $total — time to charge")
                    }
                    else -> ""
                }
                val short = to20?.let { "≈ ${Fmt.duration(it)}" } ?: d?.let { "≈ ${Fmt.duration(it.totalH)}" }
                    ?: tr("віддає", "discharging")
                Model(
                    name, Tone.DISCHARGE, pct, ring, ah, Fmt.watts(abs(s.power)), Arrow.UP,
                    // T85: малий розряд повної батареї — не стверджувати «світла нема» (інвертор може живитися від неї)
                    if (Outages.isOutageDraw(s.current, s.soc, outageA)) tr("віддає — світла нема", "discharging — no power")
                    else tr("віддає трохи — світло є?", "small draw — power on?"),
                    left, saved, "$short · $time",
                )
            }
            BatterySnapshot.Flow.CHARGING -> {
                val c = TimeLeft.charge(s.remainAh, s.fullAh, s.current)
                val line = c?.let {
                    val full = Fmt.duration(it.toFullH)
                    val rate = Fmt.num(it.percentPerHour, 0)
                    tr("до повної ≈ $full · +$rate % за год", "until full ≈ $full · +$rate % per h")
                } ?: ""
                Model(
                    name, Tone.CHARGE, pct, ring, ah, Fmt.watts(abs(s.power)), Arrow.DOWN,
                    tr("заряджається", "charging"), line, saved, tr("заряд · $time", "charging · $time"),
                )
            }
            BatterySnapshot.Flow.IDLE -> {
                val full = s.soc >= 99
                Model(
                    name, Tone.IDLE, pct, ring, ah, tr("стоїть", "idle"), Arrow.NONE,
                    if (full) tr("повна — ні заряду, ні розряду", "full — no charge, no discharge")
                    else tr("ні заряду, ні розряду", "no charge, no discharge"),
                    tr("напруга ${Fmt.volts(s.voltage)}", "voltage ${Fmt.volts(s.voltage)}"), saved,
                    (if (full) tr("повна", "full") else tr("стоїть", "idle")) + " · $time",
                )
            }
        }
    }
}

package ua.faric.aolithium.core

/**
 * Сповіщення «Заряджається» зі смужкою (v0.4.9, TODO T39 — з огляду схожих застосунків, як у BMS Widget;
 * faric 20.10: «підтримую», 19:11 «пункт 3 давай»).
 *
 * Тут лише правила без Android (тест `ChargeNoteTest`): за знімком батареї — показати («Заряджається · 64 %»,
 * смужка = заряд %, «до повної ≈ …») або прибрати. Показ у шторці — `bg/Notifier.kt` (вид [Alerts.Kind.CHARGING]).
 *
 * Щоб смужка не блимала на межі (наприкінці заряду плата ганяє балансир — струм то 0,3 А, то 0,05 А):
 * з'являється лише від [START_A], а вже показана тримається, поки струм більший за «стоїть» ([BatterySnapshot.IDLE_A]).
 * Прибираємо, коли батарея повна (100 %), плата вимкнула заряд або струм зник.
 * Нових даних нема ([STALE_MS]) — Android прибере сам (`setTimeoutAfter`), щоб у шторці не висіли старі відсотки.
 */
object ChargeNote {
    /** Від такого струму заряду смужка з'являється. */
    const val START_A = 0.5
    /** Без нового знімка стільки — сповіщення зникає само (фон читає раз на ~5 хв, уночі Android може зсунути до ~10). */
    const val STALE_MS = 20 * 60_000L

    data class Show(val title: String, val text: String, val percent: Int)

    /** [showing] — чи вже висить смужка для цієї батареї. null — прибрати (або не показувати). */
    fun of(s: BatterySnapshot, showing: Boolean): Show? {
        val floor = if (showing) BatterySnapshot.IDLE_A else START_A
        if (!s.chargeOn || s.soc >= 100 || s.current <= floor) return null
        val pct = s.soc.coerceIn(0, 100)
        val parts = mutableListOf(Fmt.watts(s.power))
        val c = TimeLeft.charge(s.remainAh, s.fullAh, s.current)
        parts += when {
            c == null -> tr("до повної — рахую…", "to full — calculating…")
            c.toFullH < 1.0 / 60 -> tr("майже повна", "almost full")
            else -> tr("до повної ≈ ${Fmt.duration(c.toFullH)}", "to full ≈ ${Fmt.duration(c.toFullH)}")
        }
        return Show(
            title = tr("Заряджається · $pct %", "Charging · $pct %"),
            text = parts.joinToString(" · "),
            percent = pct,
        )
    }
}

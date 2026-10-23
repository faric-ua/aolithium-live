package ua.faric.aolithium.guard

import android.content.Context
import android.os.Build
import ua.faric.aolithium.BuildConfig
import ua.faric.aolithium.ble.DiagFrames
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.Alerts
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Diag
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.GuardSettings
import ua.faric.aolithium.data.LastSnapshot

/**
 * T64: зібрати «Дані для перевірки» на цьому телефоні — для бота (/diag) і для кнопки в «Журналі зв'язку».
 * Текст складає `core/Diag.report` (без Android); тут лише що підставити: версії, батарея, знімок, кадри, журнал.
 */
object DiagReport {
    fun build(ctx: Context, requestedBy: String, fresh: Diag.Fresh, now: Long = System.currentTimeMillis()): String {
        val app = ctx.applicationContext
        val b = battery(app)
        val last = b?.let { LastSnapshot(app).load(it.address) }
        return Diag.report(
            Diag.Input(
                now = now,
                app = "${BuildConfig.VERSION_NAME} · збірка ${BuildConfig.VERSION_CODE}", // uk-only: файл для Claude
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                battery = b?.title ?: "—",
                board = when (b?.kind) {
                    BmsMatch.Kind.JK -> "JK BMS"
                    BmsMatch.Kind.JBD -> "JBD"
                    else -> "—"
                },
                snapshot = last?.first,
                snapshotAt = last?.second,
                frames = DiagFrames.all(),
                log = LinkLog.text().lines().takeLast(Diag.LOG_LINES),
                requestedBy = requestedBy,
                fresh = fresh,
                outage = b?.let { Alerts.describe(GuardSettings(app).state(it.address).alerts, now) },
            ),
        )
    }

    /** Про яку батарею: зі сторожем / «Записом у фоні» і найсвіжішим зв'язком, інакше — просто найсвіжіша. */
    private fun battery(ctx: Context): Batteries.Saved? {
        val all = Batteries(ctx).all().filterNot { it.address.startsWith("demo:") }
        val bg = BgSettings(ctx)
        return all.filter { bg.isOn(it.address) }.maxByOrNull { it.lastSeen } ?: all.maxByOrNull { it.lastSeen }
    }
}

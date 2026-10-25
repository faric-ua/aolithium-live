package ua.faric.aolithium.guard

import android.content.Context
import android.os.Build
import ua.faric.aolithium.BuildConfig
import ua.faric.aolithium.ble.DiagFrames
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.core.Alerts
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Diag
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.GuardSettings
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.data.SignalStore

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
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(), // T84 п. 5
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
                air = b?.let { air(app, it.address, now) }.orEmpty(),
            ),
        )
    }

    /** T81: що телефон чув в ефірі й чому фон останній раз не дістав батарею. */
    private fun air(ctx: Context, address: String, now: Long): List<String> {
        val s = SignalStore(ctx).get(address)
        val r = BgSettings(ctx).result(address)
        return listOf(
            Signal.seen(s?.rssi, s?.at, now),
            "останнє читання фону: " + Background.status(r) + (r?.note?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""), // uk-only: файл для Claude
            "геолокація: " + (if (BtAccess.locationNeeded) (if (BtAccess.locationOn(ctx)) "увімкнена" else "ВИМКНЕНА") else "не потрібна (Android 12+)"), // uk-only: файл для Claude
            "Bluetooth: " + (if (BtAccess.bluetoothOn(ctx)) "увімкнено" else "ВИМКНЕНО"), // uk-only: файл для Claude
        )
    }

    /** Про яку батарею: зі сторожем / «Записом у фоні» і найсвіжішим зв'язком, інакше — просто найсвіжіша. */
    private fun battery(ctx: Context): Batteries.Saved? {
        val all = Batteries(ctx).all().filterNot { it.address.startsWith("demo:") }
        val bg = BgSettings(ctx)
        return all.filter { bg.isOn(it.address) }.maxByOrNull { it.lastSeen } ?: all.maxByOrNull { it.lastSeen }
    }
}

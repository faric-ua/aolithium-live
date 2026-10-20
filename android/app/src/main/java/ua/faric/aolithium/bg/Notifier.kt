package ua.faric.aolithium.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ua.faric.aolithium.R
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.Alerts
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.data.AlertSettings
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.ui.MainActivity
import ua.faric.aolithium.ui.Palette

/**
 * Сповіщення (v0.3, TODO T4) — Android-частина. Правила (коли і що казати) — `core/Alerts.kt`.
 *
 * Кличуть: фоновий запис (`RecordService`, раз на ~5 хв) і відкритий екран батареї (на кожен знімок, але
 * правила ганяємо не частіше ніж раз на [EVERY_MS]). Демо-батареї не сповіщають.
 * Кожен вид — свій канал Android: звук і вигляд кожного можна змінити в налаштуваннях телефона.
 */
object Notifier {
    /** Відкритий екран дає знімок щосекунди-дві — правилам вистачить раз на 10 с. */
    const val EVERY_MS = 10_000L

    private val lastRun = HashMap<String, Long>()

    /** Новий знімок батареї. Головний потік. */
    fun onSnapshot(ctx: Context, address: String, s: BatterySnapshot, atMs: Long) {
        if (address.startsWith("demo:")) return
        val key = address.uppercase()
        val prev = lastRun[key]
        if (prev != null && atMs - prev in 0 until EVERY_MS) return
        lastRun[key] = atMs
        val settings = AlertSettings(ctx)
        val step = Alerts.onSnapshot(settings.state(address), s, atMs)
        settings.save(address, step.state)
        post(ctx, address, step.events)
    }

    /** Фон не дістав батарею: через годину без даних — тихе «Нема даних». */
    fun onNoData(ctx: Context, address: String, now: Long) {
        if (address.startsWith("demo:")) return
        val settings = AlertSettings(ctx)
        val bgOk = BgSettings(ctx).result(address)?.takeIf { it.ok }?.at
        val saved = LastSnapshot(ctx).load(address)?.second
        val lastOk = listOfNotNull(bgOk, saved).maxOrNull()
        val step = Alerts.onNoData(settings.state(address), lastOk, now)
        if (step.state != settings.state(address)) settings.save(address, step.state)
        post(ctx, address, step.events)
    }

    /**
     * Кнопка «Перевірити сповіщення» в «Ще»: показати, як виглядатиме «Світло зникло», не чекаючи відключення.
     * Тим самим каналом і з тим самим звуком; на стан правил не впливає.
     */
    fun test(ctx: Context, address: String, s: BatterySnapshot?) {
        val text = s?.takeIf { it.fullAh > 0 }?.let { Alerts.outageStartText(it.copy(current = -24.0)) }
            ?: "Батарея віддає 320 Вт · 64 % · вистачить ≈ 9 год 50 хв до 20 %"
        post(ctx, address, listOf(Alerts.Event(Alerts.Kind.OUTAGE, "Перевірка: світло зникло", "Так виглядатиме справжнє. $text")), force = true)
    }

    private fun post(ctx: Context, address: String, events: List<Alerts.Event>, force: Boolean = false) {
        if (events.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val settings = AlertSettings(ctx)
        val name = Batteries(ctx).find(address)?.title ?: BmsMatch.shortAddress(address)
        for (e in events) {
            val id = noteId(address, e.kind)
            if (e.cancel) {
                nm.cancel(id)
                continue
            }
            if (!force && !settings.isOn(e.kind)) continue
            ensureChannel(nm, e.kind)
            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_BATTERY, address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val note = Notification.Builder(ctx, channelId(e.kind))
                .setSmallIcon(R.drawable.ic_note_battery)
                .setColor(Palette.accent)
                .setContentTitle(e.title)
                .setContentText(e.text)
                .setStyle(Notification.BigTextStyle().bigText(e.text))
                .setSubText(name)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
            try {
                nm.notify(id, note)
                LinkLog.add("сповіщення ${BmsMatch.shortAddress(address)}: ${e.title}")
            } catch (x: SecurityException) {
                // Android 13+: сповіщення заборонені — у «Ще» є кнопка «Дозволити сповіщення».
                LinkLog.add("сповіщення заборонені Android: ${e.title}")
            }
        }
    }

    private fun channelId(kind: Alerts.Kind) = "alert_${kind.key}"

    /**
     * T12: мову змінили — перейменувати вже створені канали (назви видно в налаштуваннях телефона). Android при
     * повторному `createNotificationChannel` міняє лише назву й опис, а звук і важливість, які людина могла змінити,
     * лишає — тож це безпечно. Канал «Запис у фоні» — так само.
     */
    fun refreshChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        for (kind in Alerts.Kind.entries) if (nm.getNotificationChannel(channelId(kind)) != null) ensureChannel(nm, kind, force = true)
        if (nm.getNotificationChannel(RecordService.CHANNEL) != null) RecordService.ensureChannel(nm)
    }

    private fun ensureChannel(nm: NotificationManager, kind: Alerts.Kind, force: Boolean = false) {
        if (!force && nm.getNotificationChannel(channelId(kind)) != null) return
        val importance = when (kind.level) {
            Alerts.Level.HIGH -> NotificationManager.IMPORTANCE_HIGH
            Alerts.Level.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            Alerts.Level.QUIET -> NotificationManager.IMPORTANCE_LOW
        }
        val ch = NotificationChannel(channelId(kind), kind.title, importance).apply {
            description = listOf("Aolithium Live", kind.hint).filter { it.isNotBlank() }.joinToString(" · ")
        }
        nm.createNotificationChannel(ch)
    }

    /** Свій номер для кожної пари «батарея + вид»: нове того ж виду замінює старе, а не множиться. */
    fun noteId(address: String, kind: Alerts.Kind): Int =
        1_000 + ((address.uppercase().hashCode() and 0x3FFF) shl 4) + kind.ordinal
}

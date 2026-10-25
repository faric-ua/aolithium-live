package ua.faric.aolithium.data

import android.content.Context

/**
 * T81: останній сигнал кожної батареї з ефіру (фонове слухання, пошук, підключення) — для рядка під батареєю
 * в «Моїх батареях». Лише в пам'яті телефона. Пишемо не частіше ніж раз на [WRITE_MS] на батарею.
 */
class SignalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("signal", Context.MODE_PRIVATE)

    data class Seen(val rssi: Int, val at: Long)

    fun get(address: String): Seen? {
        val key = address.uppercase()
        val at = prefs.getLong("at:$key", 0L)
        if (at <= 0L) return null
        return Seen(prefs.getInt("rssi:$key", 0), at)
    }

    fun save(address: String, rssi: Int, at: Long = System.currentTimeMillis()) {
        val key = address.uppercase()
        val old = get(key)
        if (old != null && at - old.at < WRITE_MS && old.rssi == rssi) return
        prefs.edit().putInt("rssi:$key", rssi).putLong("at:$key", at).apply()
    }

    private companion object {
        const val WRITE_MS = 30_000L
    }
}

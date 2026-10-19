package ua.faric.aolithium.data

import android.content.Context
import ua.faric.aolithium.core.Alerts

/**
 * Сповіщення (v0.3): які види увімкнені (спільно для всіх батарей, за замовчуванням — усі) і що вже сказано
 * по кожній батареї ([Alerts.State], щоб не повторювати). Лише в пам'яті телефона.
 */
class AlertSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun isOn(kind: Alerts.Kind): Boolean = prefs.getBoolean("$KEY_ON${kind.key}", true)

    fun set(kind: Alerts.Kind, on: Boolean) {
        prefs.edit().putBoolean("$KEY_ON${kind.key}", on).apply()
    }

    fun state(address: String): Alerts.State = Alerts.State.decode(prefs.getString(stateKey(address), null))

    fun save(address: String, st: Alerts.State) {
        prefs.edit().putString(stateKey(address), st.encode()).apply()
    }

    private fun stateKey(address: String) = KEY_STATE + address.uppercase()

    private companion object {
        const val KEY_ON = "on:"
        const val KEY_STATE = "state:"
    }
}

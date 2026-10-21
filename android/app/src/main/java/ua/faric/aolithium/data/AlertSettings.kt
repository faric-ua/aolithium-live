package ua.faric.aolithium.data

import android.content.Context
import ua.faric.aolithium.core.Alerts
import ua.faric.aolithium.core.Emergency

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

    /**
     * T50: кому дзвонити при серйозній тривозі — 101 (за замовчуванням так) і свій номер (сусід, родич). Спільне для
     * шторки цього телефона й бота сторожа. Лише в пам'яті телефона.
     */
    var callPlan: Emergency.Plan
        get() = Emergency.Plan(prefs.getBoolean(KEY_RESCUE, true), Emergency.decodeContact(prefs.getString(KEY_CONTACT, "")))
        set(v) {
            prefs.edit().putBoolean(KEY_RESCUE, v.rescue).putString(KEY_CONTACT, Emergency.encodeContact(v.contact)).apply()
        }

    private companion object {
        const val KEY_ON = "on:"
        const val KEY_STATE = "state:"
        const val KEY_RESCUE = "call:rescue"
        const val KEY_CONTACT = "call:contact"
    }
}

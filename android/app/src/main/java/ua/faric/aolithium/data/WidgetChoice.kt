package ua.faric.aolithium.data

import android.content.Context

/**
 * T22: яку батарею показує кожен віджет (номер віджета → адреса батареї або «автоматично»). Лише в пам'яті телефона.
 * null — ще не вибирали (віджети з версій до 0.4.16): такий віджет працює «автоматично», як раніше.
 */
class WidgetChoice(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(widgetId: Int): String? = prefs.getString(key(widgetId), null)

    fun set(widgetId: Int, address: String) {
        prefs.edit().putString(key(widgetId), address).apply()
    }

    /** Віджет прибрали з екрана — забути його вибір. */
    fun remove(widgetId: Int) {
        prefs.edit().remove(key(widgetId)).apply()
    }

    private fun key(id: Int) = "w$id"
}

package ua.faric.aolithium.data

import android.content.Context

/**
 * Позначки faric у пам'яті телефона: дні, коли працював генератор (для батареї світло й генератор однакові —
 * сам застосунок їх не розрізнить; faric 19.10 03:31). Окремо для кожної батареї. День — «РРРР-ММ-ДД» місцевого часу.
 * Плюс вибір «Вати / Ампери» на спідометрі.
 */
class Marks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("marks", Context.MODE_PRIVATE)

    fun generatorDays(address: String): Set<String> = prefs.getStringSet(key(address), emptySet()).orEmpty().toSet()

    /** Поставити або зняти позначку; повертає, чи тепер день позначено. */
    fun toggleGenerator(address: String, day: String): Boolean {
        val now = generatorDays(address).toMutableSet()
        val marked = if (day in now) { now.remove(day); false } else { now.add(day); true }
        prefs.edit().putStringSet(key(address), now).apply()
        return marked
    }

    var gaugeAmps: Boolean
        get() = prefs.getBoolean("gaugeAmps", false)
        set(value) = prefs.edit().putBoolean("gaugeAmps", value).apply()

    private fun key(address: String) = "gen:$address"
}

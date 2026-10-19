package ua.faric.aolithium.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import ua.faric.aolithium.core.Background

/**
 * Запис у фоні (v0.2.5): для яких батарей увімкнено і чим закінчилось останнє читання.
 * Лише в пам'яті телефона (SharedPreferences), нікуди не передається.
 */
class BgSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("background", Context.MODE_PRIVATE)

    fun enabled(): Set<String> = prefs.getStringSet(KEY_ON, emptySet()).orEmpty().toSet()

    fun isOn(address: String): Boolean = enabled().any { it.equals(address, ignoreCase = true) }

    fun set(address: String, on: Boolean) {
        val now = enabled().filterNot { it.equals(address, ignoreCase = true) }.toMutableSet()
        if (on && !address.startsWith("demo:")) now += address
        prefs.edit().putStringSet(KEY_ON, now).apply()
    }

    /** Кнопка «Вимкнути» у сповіщенні — вимикає для всіх батарей. */
    fun offAll() {
        prefs.edit().putStringSet(KEY_ON, emptySet()).apply()
    }

    fun saveResult(address: String, r: Background.Result) {
        val o = JSONObject().put("at", r.at).put("ok", r.ok).put("soc", r.soc).put("a", r.current).put("note", r.note)
        prefs.edit().putString("$KEY_RESULT$address", o.toString()).apply()
    }

    fun result(address: String): Background.Result? {
        val raw = prefs.getString("$KEY_RESULT$address", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Background.Result(o.getLong("at"), o.getBoolean("ok"), o.optInt("soc"), o.optDouble("a", 0.0), o.optString("note"))
        } catch (e: JSONException) {
            null
        }
    }

    var lastTickAt: Long
        get() = prefs.getLong(KEY_TICK, 0L)
        set(v) {
            prefs.edit().putLong(KEY_TICK, v).apply()
        }

    private companion object {
        const val KEY_ON = "on"
        const val KEY_RESULT = "result:"
        const val KEY_TICK = "lastTick"
    }
}

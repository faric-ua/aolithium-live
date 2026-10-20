package ua.faric.aolithium.data

import android.content.Context
import ua.faric.aolithium.core.Lang

/** T12: вибрана мова інтерфейсу — лише в пам'яті телефона. За замовчуванням українська (не мова системи). */
object LangSettings {
    private const val PREFS = "ui"
    private const val KEY = "lang"

    fun load(context: Context) {
        Lang.current = Lang.of(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun save(context: Context, code: Lang.Code) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code.tag).apply()
        Lang.current = code
    }
}

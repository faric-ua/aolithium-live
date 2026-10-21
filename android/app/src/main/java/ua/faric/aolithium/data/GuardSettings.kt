package ua.faric.aolithium.data

import android.content.Context
import java.io.File
import ua.faric.aolithium.core.Guard
import ua.faric.aolithium.core.GuardBot
import ua.faric.aolithium.core.GuardPicture

/**
 * T48 «Сторож (Telegram)»: усе — лише в пам'яті цього телефона (SharedPreferences). Токен бота й учасники — ніде
 * більше: ні в репо, ні в журналі зв'язку, ні в історії. Вимкнено за замовчуванням.
 */
class GuardSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** T59: картинки батареї, що чекають у черзі (`files/guard-pics/`). Надіслане чи викинуте з черги — стирається. */
    val picDir: File get() = File(app.filesDir, "guard-pics")

    var enabled: Boolean
        get() = prefs.getBoolean("on", false)
        set(v) { prefs.edit().putBoolean("on", v).apply() }

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(v) { prefs.edit().putString("token", v).apply() }

    /** «@aol_home_bot» з `getMe` — щоб показати й зібрати посилання-запрошення. */
    var botName: String
        get() = prefs.getString("bot", "").orEmpty()
        set(v) { prefs.edit().putString("bot", v).apply() }

    var code: String
        get() = prefs.getString("code", "").orEmpty()
        set(v) { prefs.edit().putString("code", v).apply() }

    var members: List<GuardBot.Member>
        get() = GuardBot.decode(prefs.getString("members", ""))
        set(v) { prefs.edit().putString("members", GuardBot.encode(v)).apply() }

    /** Номер наступного оновлення Telegram (щоб не читати те саме двічі). */
    var offset: Long
        get() = prefs.getLong("offset", 0L)
        set(v) { prefs.edit().putLong("offset", v).apply() }

    /** День останнього «я працюю» (20261021). */
    var dailyDay: Int
        get() = prefs.getInt("dailyDay", 0)
        set(v) { prefs.edit().putInt("dailyDay", v).apply() }

    var dailyHour: Int
        get() = prefs.getInt("dailyHour", Guard.DAILY_HOUR_DEFAULT)
        set(v) { prefs.edit().putInt("dailyHour", v).apply() }

    /** Остання помилка Telegram людською мовою ("" — нема) і коли востаннє вдалося щось надіслати. */
    var lastError: String
        get() = prefs.getString("err", "").orEmpty()
        set(v) { prefs.edit().putString("err", v).apply() }

    var lastSentAt: Long
        get() = prefs.getLong("sentAt", 0L)
        set(v) { prefs.edit().putLong("sentAt", v).apply() }

    fun state(address: String): Guard.State = Guard.State.decode(prefs.getString("st:${address.uppercase()}", null))

    fun save(address: String, st: Guard.State) {
        prefs.edit().putString("st:${address.uppercase()}", st.encode()).apply()
    }

    /**
     * Черга ненадісланого (нема інтернету — наприклад, світло зникло й роутер вимкнувся): кому, текст і (T59) картинка.
     * Формат рядка — `GuardPicture.encode`. Не більше [OUTBOX_MAX] — найстаріші випадають.
     */
    var outbox: List<GuardPicture.Out>
        get() = GuardPicture.decode(prefs.getString("outbox", ""))
        set(v) {
            prefs.edit().putString("outbox", GuardPicture.encode(v.takeLast(OUTBOX_MAX))).apply()
        }

    /** Стерти картинки, яких уже нема в черзі. */
    fun cleanPics() {
        val keep = outbox.mapNotNull { it.photo }.toSet()
        picDir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
    }

    /** Вимкнути сторожа: токен, учасники й код лишаються (увімкнути знову — без нового налаштування). */
    fun turnOff() {
        prefs.edit().putBoolean("on", false).putString("outbox", "").apply()
        picDir.listFiles().orEmpty().forEach { it.delete() }
    }

    companion object {
        const val OUTBOX_MAX = 60
    }
}

package ua.faric.aolithium.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ua.faric.aolithium.core.BmsMatch

/**
 * «Мої батареї» — ті, до яких уже підключались. Лише в пам'яті телефона (SharedPreferences),
 * нікуди не передається. Повна адреса Bluetooth потрібна, щоб підключатись без пошуку.
 */
class Batteries(context: Context) {
    data class Saved(val address: String, val name: String, val nickname: String, val kind: BmsMatch.Kind, val lastSeen: Long) {
        val title: String get() = nickname.ifBlank { name.ifBlank { BmsMatch.shortAddress(address) } }
    }

    private val prefs = context.applicationContext.getSharedPreferences("batteries", Context.MODE_PRIVATE)

    fun all(): List<Saved> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val address = o.optString("address")
                if (address.isBlank()) null
                else Saved(
                    address = address,
                    name = o.optString("name"),
                    nickname = o.optString("nickname"),
                    kind = BmsMatch.Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: BmsMatch.Kind.JBD,
                    lastSeen = o.optLong("lastSeen"),
                )
            }.sortedByDescending { it.lastSeen }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    fun find(address: String): Saved? = all().firstOrNull { it.address.equals(address, ignoreCase = true) }

    /** Запам'ятати (або оновити назву й час) після вдалого читання. */
    fun remember(address: String, name: String?, kind: BmsMatch.Kind) {
        val old = find(address)
        put(
            Saved(
                address = address,
                name = name?.takeIf { it.isNotBlank() } ?: old?.name.orEmpty(),
                nickname = old?.nickname.orEmpty(),
                kind = kind,
                lastSeen = System.currentTimeMillis(),
            ),
        )
    }

    fun rename(address: String, nickname: String) {
        val old = find(address) ?: return
        put(old.copy(nickname = nickname.trim().take(40)))
    }

    /** Прибрати зі списку (саму батарею це не чіпає). */
    fun forget(address: String) {
        save(all().filterNot { it.address.equals(address, ignoreCase = true) })
    }

    private fun put(s: Saved) {
        save(all().filterNot { it.address.equals(s.address, ignoreCase = true) } + s)
    }

    private fun save(list: List<Saved>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("address", s.address)
                    .put("name", s.name)
                    .put("nickname", s.nickname)
                    .put("kind", s.kind.name)
                    .put("lastSeen", s.lastSeen),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "saved"

        /** Назви з останнього пошуку (у пам'яті): запам'ятати батарею з назвою, коли вона вперше відповість. */
        val scanNames = HashMap<String, String>()
    }
}

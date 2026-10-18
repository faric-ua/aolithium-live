package ua.faric.aolithium.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.Protection
import ua.faric.aolithium.core.Ymd

/**
 * Останній прочитаний стан кожної батареї — щоб без зв'язку (батарея далеко) застосунок показував
 * усі вкладки з останніми числами й часом, коли їх прочитано (faric 18.10 21:48). Лише в пам'яті телефона.
 */
class LastSnapshot(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_snapshot", Context.MODE_PRIVATE)

    /** Зберігаємо не частіше ніж раз на 30 с (кожні 2 с — зайве для пам'яті телефона). */
    fun save(address: String, s: BatterySnapshot, atMs: Long) {
        if (address.startsWith("demo:")) return
        val prev = prefs.getLong("$address.at", 0)
        if (atMs - prev < SAVE_EVERY_MS) return
        prefs.edit().putString(address, encode(s).toString()).putLong("$address.at", atMs).apply()
    }

    fun load(address: String): Pair<BatterySnapshot, Long>? {
        val raw = prefs.getString(address, null) ?: return null
        return try {
            decode(JSONObject(raw)) to prefs.getLong("$address.at", 0)
        } catch (e: JSONException) {
            null
        }
    }

    private fun encode(s: BatterySnapshot) = JSONObject()
        .put("v", s.voltage).put("a", s.current).put("soc", s.soc).put("rem", s.remainAh).put("full", s.fullAh)
        .put("cyc", s.cycles).put("cells", JSONArray(s.cellsMv)).put("temps", JSONArray(s.temperatures))
        .put("bal", JSONArray(s.balancingCells)).put("prot", JSONArray(s.protections.map { it.name }))
        .put("unk", JSONArray(s.unknownProtectionBits)).put("chg", s.chargeOn).put("dis", s.dischargeOn)
        .put("fw", s.firmware ?: "").put("made", s.made?.let { "${it.year}-${it.month}-${it.day}" } ?: "")
        .put("model", s.model ?: "").put("cc", s.cellCount)
        .put("cnt", s.errorCounts?.let { list -> JSONArray(list.map { JSONArray().put(it.first).put(it.second) }) } ?: JSONArray())
        .put("hasCnt", s.errorCounts != null)

    private fun decode(o: JSONObject): BatterySnapshot {
        fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        fun doubles(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getDouble(it) } }.orEmpty()
        val made = o.optString("made").split('-').takeIf { it.size == 3 }?.let { p ->
            runCatching { Ymd(p[0].toInt(), p[1].toInt(), p[2].toInt()) }.getOrNull()
        }
        val counts = if (!o.optBoolean("hasCnt")) null else o.optJSONArray("cnt")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0) to it.getInt(1) } }
        }
        val prot = o.optJSONArray("prot")?.let { a -> (0 until a.length()).mapNotNull { i -> Protection.entries.firstOrNull { it.name == a.getString(i) } } }.orEmpty()
        return BatterySnapshot(
            voltage = o.getDouble("v"), current = o.getDouble("a"), soc = o.getInt("soc"), remainAh = o.getDouble("rem"),
            fullAh = o.getDouble("full"), cycles = o.getInt("cyc"), cellsMv = ints("cells"), temperatures = doubles("temps"),
            balancingCells = ints("bal"), protections = prot, unknownProtectionBits = ints("unk"), chargeOn = o.getBoolean("chg"),
            dischargeOn = o.getBoolean("dis"), firmware = o.optString("fw").ifBlank { null }, made = made,
            model = o.optString("model").ifBlank { null }, errorCounts = counts, cellCount = o.optInt("cc"),
        )
    }

    private companion object {
        const val SAVE_EVERY_MS = 30_000L
    }
}

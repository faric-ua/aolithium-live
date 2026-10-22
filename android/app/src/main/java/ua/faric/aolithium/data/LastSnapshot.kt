package ua.faric.aolithium.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.JbdParser
import ua.faric.aolithium.core.JkExtra
import ua.faric.aolithium.core.JkSettings
import ua.faric.aolithium.core.Protection
import ua.faric.aolithium.core.Ymd

/**
 * Останній прочитаний стан кожної батареї — щоб без зв'язку (батарея далеко) застосунок показував
 * усі вкладки з останніми числами й часом, коли їх прочитано (faric 18.10 21:48). Лише в пам'яті телефона.
 */
class LastSnapshot(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_snapshot", Context.MODE_PRIVATE)

    /**
     * Зберігаємо не частіше ніж раз на 30 с (кожні 2 с з екрана — зайве для пам'яті телефона).
     * [always] — для фону (раз на 5 хв або на прохання /diag): його знімок не можна відкидати (T66, 22.10: фон прочитав через
     * 29 с після екрана — і у файлі /diag лишився старіший знімок екрана).
     */
    fun save(address: String, s: BatterySnapshot, atMs: Long, always: Boolean = false) {
        if (address.startsWith("demo:")) return
        val prev = prefs.getLong("$address.at", 0)
        if (!always && atMs - prev < SAVE_EVERY_MS) return
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
        .apply { s.jk?.let { put("jk", encodeJk(it)) } }

    /** v0.4: частина JK — щоб і без зв'язку було видно балансир, опір дротів, ключі. */
    private fun encodeJk(j: JkExtra) = JSONObject()
        .put("lay", j.layout).put("bi", j.balanceCurrent).put("ba", j.balancerAction).put("wire", JSONArray(j.wireMohm))
        .put("mos", j.mosTemp ?: JSONObject.NULL).put("soh", j.soh).put("cap", j.cycleCapacityAh).put("run", j.runtimeSec)
        .put("heat", j.heatingOn).put("hi", j.heatingCurrent).put("plug", j.chargerPlugged).put("stage", j.chargeStage ?: -1)
        .put("hw", j.hw ?: "").put("sn", j.serial ?: "")
        .apply { j.settings?.let { put("set", it.encode()) } }

    private fun decodeJk(o: JSONObject): JkExtra = JkExtra(
        layout = o.optString("lay"), balanceCurrent = o.optDouble("bi", 0.0), balancerAction = o.optInt("ba"),
        wireMohm = o.optJSONArray("wire")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
        mosTemp = if (o.isNull("mos")) null else o.optDouble("mos"), soh = o.optInt("soh"), cycleCapacityAh = o.optDouble("cap", 0.0),
        runtimeSec = o.optLong("run"), heatingOn = o.optBoolean("heat"), heatingCurrent = o.optDouble("hi", 0.0),
        chargerPlugged = o.optBoolean("plug"), chargeStage = o.optInt("stage", -1).takeIf { it >= 0 },
        hw = o.optString("hw").ifBlank { null }, serial = o.optString("sn").ifBlank { null },
        settings = JkSettings.decode(o.optString("set").ifBlank { null }),
    )

    private fun decode(o: JSONObject): BatterySnapshot {
        fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        fun doubles(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getDouble(it) } }.orEmpty()
        val made = o.optString("made").split('-').takeIf { it.size == 3 }?.let { p ->
            runCatching { Ymd(p[0].toInt(), p[1].toInt(), p[2].toInt()) }.getOrNull()
        }
        // T38: назви лічильників — ключі в порядку протоколу; збережені до 0.4.5 мали «Переразряд…» — беремо нинішні за номером.
        val names = JbdParser.ERROR_COUNT_NAMES
        val counts = if (!o.optBoolean("hasCnt")) null else o.optJSONArray("cnt")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONArray(i).let { (if (a.length() == names.size) names[i] else it.getString(0)) to it.getInt(1) }
            }
        }
        val prot = o.optJSONArray("prot")?.let { a -> (0 until a.length()).mapNotNull { i -> Protection.entries.firstOrNull { it.name == a.getString(i) } } }.orEmpty()
        return BatterySnapshot(
            voltage = o.getDouble("v"), current = o.getDouble("a"), soc = o.getInt("soc"), remainAh = o.getDouble("rem"),
            fullAh = o.getDouble("full"), cycles = o.getInt("cyc"), cellsMv = ints("cells"), temperatures = doubles("temps"),
            balancingCells = ints("bal"), protections = prot, unknownProtectionBits = ints("unk"), chargeOn = o.getBoolean("chg"),
            dischargeOn = o.getBoolean("dis"), firmware = o.optString("fw").ifBlank { null }, made = made,
            model = o.optString("model").ifBlank { null }, errorCounts = counts, cellCount = o.optInt("cc"),
            jk = o.optJSONObject("jk")?.let { decodeJk(it) },
        )
    }

    private companion object {
        const val SAVE_EVERY_MS = 30_000L
    }
}

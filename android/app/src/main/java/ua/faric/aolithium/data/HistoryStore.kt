package ua.faric.aolithium.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.DemoHistory
import ua.faric.aolithium.core.HistoryCsv
import ua.faric.aolithium.core.HistoryMath
import ua.faric.aolithium.core.HistoryPoint

/**
 * Історія показників у пам'яті телефона: `files/history/<батарея>/РРРР-ММ-ДД.csv`, рядок на 10 с.
 * Лише на цьому телефоні, нікуди не передається. Старше [KEEP_DAYS] днів — прибирається.
 * Запис і читання — у своєму потоці (диск не гальмує екран).
 */
class HistoryStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "history")
    private val thread = HandlerThread("history").apply { start() }
    private val io = Handler(thread.looper)
    private val lastAt = HashMap<String, Long>()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    /** Записати, якщо з попереднього запису цієї батареї минуло ≥ 10 с. */
    fun record(address: String, s: BatterySnapshot, atMs: Long) {
        if (address.startsWith("demo:")) return
        val prev = lastAt[address] ?: 0L
        if (atMs - prev < HistoryMath.RECORD_EVERY_MS - 500) return
        lastAt[address] = atMs
        val line = HistoryPoint.of(s, atMs).toCsv() + "\n"
        io.post {
            try {
                val f = File(dir(address), day.format(Date(atMs)) + ".csv")
                f.parentFile?.mkdirs()
                f.appendText(line)
                prune(address)
            } catch (e: java.io.IOException) {
                // місце скінчилось чи інша біда з диском — показники все одно видно наживо
            }
        }
    }

    /** Точки між [from] і [to]; [done] — у головному потоці. */
    fun load(address: String, from: Long, to: Long, done: (List<HistoryPoint>) -> Unit) {
        val main = Handler(android.os.Looper.getMainLooper())
        if (address.startsWith("demo:")) {
            io.post {
                val profile = DemoHistory.profileFor(address.removePrefix("demo:"))
                val pts = DemoHistory.week(System.currentTimeMillis(), profile).filter { it.t in from..to }
                main.post { done(pts) }
            }
            return
        }
        io.post {
            val out = ArrayList<HistoryPoint>()
            var t = from - from % DAY_MS - DAY_MS
            while (t <= to + DAY_MS) {
                val f = File(dir(address), day.format(Date(t)) + ".csv")
                if (f.isFile) {
                    try {
                        f.forEachLine { line -> HistoryPoint.parse(line)?.let { if (it.t in from..to) out += it } }
                    } catch (e: java.io.IOException) {
                        // пропускаємо пошкоджений файл
                    }
                }
                t += DAY_MS
            }
            out.sortBy { it.t }
            main.post { done(out) }
        }
    }

    /**
     * Уся історія батареї одним CSV (кнопка «Зберегти CSV» у «Ще»). Кличеться не з головного потоку.
     * Спершу BOM — щоб Excel побачив українські назви колонок.
     */
    fun writeCsv(address: String, out: java.io.OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("\uFEFF" + HistoryCsv.HEADER + "\n")
        val files = dir(address).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            try {
                f.forEachLine { line -> HistoryPoint.parse(line)?.let { w.write(HistoryCsv.row(it) + "\n") } }
            } catch (e: java.io.IOException) {
                // пошкоджений файл — пропускаємо
            }
        }
        w.flush()
    }

    /** Скільки днів і байт записано (для «Налаштувань»). */
    fun size(address: String): Pair<Int, Long> {
        val files = dir(address).listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** Стерти історію однієї батареї (лише після підтвердження у вікні). */
    fun clear(address: String, done: () -> Unit) {
        val main = Handler(android.os.Looper.getMainLooper())
        io.post {
            dir(address).listFiles { f -> f.name.endsWith(".csv") }?.forEach { it.delete() }
            main.post(done)
        }
    }

    private fun dir(address: String) = File(root, address.replace(':', '_').filter { it.isLetterOrDigit() || it == '_' })

    private var lastPrune = 0L

    private fun prune(address: String) {
        val now = System.currentTimeMillis()
        if (now - lastPrune < DAY_MS / 4) return
        lastPrune = now
        val oldest = day.format(Date(now - KEEP_DAYS * DAY_MS))
        dir(address).listFiles { f -> f.name.endsWith(".csv") && f.name.removeSuffix(".csv") < oldest }?.forEach { it.delete() }
    }

    companion object {
        const val KEEP_DAYS = 90L
        const val DAY_MS = 86_400_000L

        @Volatile private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context).also { instance = it } }
    }
}

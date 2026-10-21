package ua.faric.aolithium.core

import java.io.File
import java.io.FileOutputStream

/**
 * T57 «Журнал зв'язку частинами» (faric 21.10 14:57): журнал пишеться у файли-частини в пам'яті застосунку, щоб
 * переживав перезапуск і не ріс без кінця. Кожна частина — до [partLines] рядків, перший рядок знає попередню
 * («# частина 12 · попередня 11»); частини понад [keepParts] — найстаріші — стираються. На екрані — лише свіжий
 * хвіст (мало, «не нагружає систему»); коли треба вся історія — [all] іде по частинах назад і збирає все в одне.
 *
 * Без Android (лише файли) — щоб перевірити тестами. Адресу батареї й токени сюди не пишуть ті, хто кличе [add].
 */
class LogBook(private val dir: File, private val partLines: Int = PART_LINES, private val keepParts: Int = KEEP_PARTS) {
    private var part = -1
    private var linesInPart = 0

    @Synchronized
    fun add(line: String) {
        try {
            if (part < 0) resume()
            if (linesInPart >= partLines) startNext()
            FileOutputStream(file(part), true).use { it.write((line.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8)) }
            linesInPart++
        } catch (e: Exception) {
            // журнал — допомога, не головне: нема місця чи доступу — просто не пишемо
        }
    }

    /** Номери частин на диску — від найстарішої. */
    @Synchronized
    fun parts(): List<Int> = dir.listFiles().orEmpty().mapNotNull { numberOf(it.name) }.sorted()

    /** Останні [n] рядків журналу (без заголовків частин) — від найстаріших до найсвіжіших. */
    @Synchronized
    fun recent(n: Int): List<String> {
        val out = ArrayDeque<String>()
        for (p in parts().asReversed()) {
            val lines = read(p).filterNot { it.startsWith(HEAD) }
            for (l in lines.asReversed()) {
                out.addFirst(l)
                if (out.size >= n) return out.toList()
            }
        }
        return out.toList()
    }

    /**
     * Уся історія одним текстом — найсвіжіші зверху (як у вікні, T47), з рядками-межами частин, щоб було видно,
     * звідки що.
     */
    @Synchronized
    fun all(): String = buildString {
        for (p in parts().asReversed()) {
            val lines = read(p)
            for (l in lines.asReversed()) if (!l.startsWith(HEAD)) append(l).append('\n')
            lines.firstOrNull { it.startsWith(HEAD) }?.let { append(it).append('\n') }
        }
    }.trimEnd()

    @Synchronized
    fun clear() {
        dir.listFiles().orEmpty().filter { numberOf(it.name) != null }.forEach { it.delete() }
        part = -1
        linesInPart = 0
    }

    private fun resume() {
        dir.mkdirs()
        val last = parts().lastOrNull()
        if (last == null) {
            part = 0
            startNext()
        } else {
            part = last
            linesInPart = read(last).count { !it.startsWith(HEAD) }
        }
    }

    private fun startNext() {
        part++
        val prev = part - 1
        val head = if (prev >= 1) tr("$HEAD частина $part · попередня $prev", "$HEAD part $part · previous $prev")
            else tr("$HEAD частина $part", "$HEAD part $part")
        FileOutputStream(file(part), false).use { it.write((head + "\n").toByteArray(Charsets.UTF_8)) }
        linesInPart = 0
        val all = parts()
        if (all.size > keepParts) all.take(all.size - keepParts).forEach { file(it).delete() }
    }

    private fun read(p: Int): List<String> = try {
        file(p).readLines(Charsets.UTF_8)
    } catch (e: Exception) {
        emptyList()
    }

    private fun file(p: Int) = File(dir, "linklog-%06d.txt".format(p))

    companion object {
        const val PART_LINES = 200
        const val KEEP_PARTS = 20
        const val HEAD = "#"

        /** Назва файлу «Зберегти все»: «aolithium-journal-2026-10-21-1830.txt» (без адреси батареї). */
        fun fileName(ms: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT).apply { timeZone = tz }
            return "aolithium-journal-${f.format(java.util.Date(ms))}.txt"
        }

        fun numberOf(name: String): Int? = Regex("""linklog-(\d{6})\.txt""").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}

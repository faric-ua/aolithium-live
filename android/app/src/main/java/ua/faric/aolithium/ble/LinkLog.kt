package ua.faric.aolithium.ble

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ua.faric.aolithium.core.LogBook
import ua.faric.aolithium.core.tr

/**
 * Журнал зв'язку з платою: що відправили, що прийшло (байти), помилки Android.
 * На екрані батареї — кнопка «Журнал зв'язку»: його можна переслати Claude, коли щось не так (як скрін, але точніше).
 * Повну адресу батареї сюди не пишемо — лише скорочену.
 *
 * T57 (faric 21.10 14:57): у пам'яті — лише свіжий хвіст ([MAX] рядків, для вікна); усе пишеться ще й у файли-частини
 * (`files/linklog/`, `core/LogBook`) — журнал переживає перезапуск, а «Зберегти все» збирає всі частини в один файл.
 */
object LinkLog {
    const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private var book: LogBook? = null

    /** Старт процесу (`AolithiumApp`): підхопити записане до перезапуску. */
    @Synchronized
    fun init(filesDir: File) {
        if (book != null) return
        val b = LogBook(File(filesDir, "linklog"))
        book = b
        val old = b.recent(MAX)
        lines.clear()
        lines.addAll(old)
    }

    @Synchronized
    fun add(line: String) {
        val l = time.format(Date()) + "  " + line
        lines.addLast(l)
        while (lines.size > MAX) lines.removeFirst()
        book?.add(l)
    }

    /** Уся історія з усіх частин — найсвіжіші зверху (для «Зберегти все»). */
    @Synchronized
    fun allNewestFirst(): String = book?.all() ?: newestFirst()

    /** Скільки частин на диску (для підпису у вікні). */
    @Synchronized
    fun partCount(): Int = book?.parts()?.size ?: 0

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** T47 (faric 20.10 22:53): у вікні й у «Поділитися» — найсвіжіші рядки зверху. */
    @Synchronized
    fun newestFirst(): String = lines.reversed().joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        book?.clear()
    }

    fun hex(b: ByteArray, max: Int = 64): String {
        val s = b.take(max).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        return if (b.size > max) "$s … (${b.size} " + tr("байт", "bytes") + ")" else s
    }
}

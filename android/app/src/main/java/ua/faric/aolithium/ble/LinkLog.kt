package ua.faric.aolithium.ble

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ua.faric.aolithium.core.tr

/**
 * Журнал зв'язку з платою: що відправили, що прийшло (байти), помилки Android.
 * Лише в пам'яті, останні [MAX] рядків. На екрані батареї — кнопка «Журнал зв'язку»: його можна
 * переслати Claude, коли щось не так (як скрін, але точніше).
 * Повну адресу батареї сюди не пишемо — лише скорочену.
 */
object LinkLog {
    const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        lines.addLast(time.format(Date()) + "  " + line)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** T47 (faric 20.10 22:53): у вікні й у «Поділитися» — найсвіжіші рядки зверху. */
    @Synchronized
    fun newestFirst(): String = lines.reversed().joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    fun hex(b: ByteArray, max: Int = 64): String {
        val s = b.take(max).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        return if (b.size > max) "$s … (${b.size} " + tr("байт", "bytes") + ")" else s
    }
}

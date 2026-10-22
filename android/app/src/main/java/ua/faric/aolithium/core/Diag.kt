package ua.faric.aolithium.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * T64 (faric 22.10 01:06: «щоб батарею мамину почитали, отримали необхідні дані і сюди прислали»): **дані для перевірки**
 * на відстані. У групі: `/diag` → телефон-сторож читає батарею → бот надсилає файл .txt: що плата відповіла (байтами),
 * що застосунок з цього порахував, самоперевірки (сума комірок = напруга тощо) і журнал зв'язку. faric пересилає файл
 * Claude — той звіряє розбір, не їдучи до батареї.
 *
 * Тут — без Android: які кадри тримати, **що затерти** і як скласти текст (тест `DiagTest`).
 *
 * Що затираємо: плата JK у кадрі «хто ти» (0x03) шле ще й **паролі плати** (місця — esphome-jk-bms,
 * `decode_jk02_device_info_`: 62–77 пароль пристрою, 97–101 пароль, 118–133 пароль налаштувань) і серійний номер (86–96).
 * Лишаємо лише модель, версії, лічильники й назву (0–61) і дату виготовлення (78–85); решту кадру — нулі. Адреси
 * Bluetooth у кадрах нема; у журналі — лише її скорочення, як і в «Журналі зв'язку».
 */
object Diag {
    /** Одна відповідь плати: коли, яка плата, тип кадру (JK) або команда (JBD), байти (JBD — лише дані відповіді). */
    data class Raw(val at: Long, val board: String, val type: Int, val bytes: ByteArray, val status: Int = 0) {
        override fun equals(other: Any?): Boolean =
            other is Raw && at == other.at && board == other.board && type == other.type && status == other.status && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = (at.hashCode() * 31 + type) * 31 + bytes.contentHashCode()
    }

    const val JBD = "JBD"
    const val JK = "JK"

    /** Скільки свіжих кадрів кожного типу тримати і скільки всього. */
    const val PER_TYPE = 2
    const val MAX_FRAMES = 16

    /** Як часто можна просити /diag (щоб група не засипала сторожа запитами). */
    const val COOLDOWN_MS = 2 * 60_000L

    /** Скільки чекати свіжого читання, перш ніж скласти файл. */
    const val WAIT_MS = 45_000L

    /** T66: як часто сторож дивиться, чи фон уже дочитав. */
    const val POLL_MS = 2_000L

    /**
     * T66 (файл faric 22.10 02:47 прийшов через ~50 с, хоча фон дочитав за 4 с): складати файл, щойно фон дочитав —
     * є свіжі кадри від плати і фон більше не читає; інакше — не довше за [WAIT_MS].
     */
    fun readyToSend(elapsedMs: Long, freshFrames: Boolean, stillReading: Boolean, readFinished: Boolean = false): Boolean =
        elapsedMs >= WAIT_MS || (!stillReading && (freshFrames || readFinished))

    /*
     * T73 (файл faric 22.10 21:54, перевірка 61): телефон на межі дальності — фон тричі «код 133» і здався за 19 с, а файл
     * прийшов лише через 45 с. Умова T66 думала тільки про вдачу («є свіжі кадри»); невдача — теж кінець роботи фону, але
     * свіжих кадрів по ній нема. Тепер [readyToSend] знає й про це: `readFinished` — фон **почав і закінчив** читання
     * після запиту (лічильник закінчених читань у службі виріс), вдало чи ні. Лічильник, а не «фон зараз не читає»:
     * одразу після запиту фон ще може не встигнути почати — тоді «не читає» було б хибним «уже все».
     */

    /** Чи свіже читання у файлі і чому ні — щоб Claude одразу бачив, звідки дані. */
    enum class Fresh { YES, NOT_REACHED, TIMEOUT, LAST }

    /** Що сталося з читанням на запит: свіжі кадри є — так; фон закінчив без них — не дістав; інакше — не встиг. */
    fun freshness(freshFrames: Boolean, readFinished: Boolean): Fresh = when {
        freshFrames -> Fresh.YES
        readFinished -> Fresh.NOT_REACHED
        else -> Fresh.TIMEOUT
    }

    fun freshText(f: Fresh): String = when (f) { // uk-only: файл для Claude
        Fresh.YES -> "так" // uk-only: файл для Claude
        Fresh.NOT_REACHED -> "ні — фон не дістав батарею (далеко чи зайнята іншим телефоном), нижче останні дані" // uk-only: файл для Claude
        Fresh.TIMEOUT -> "ні — фон не дочитав за ${WAIT_MS / 1000} с, нижче останні дані" // uk-only: файл для Claude
        Fresh.LAST -> "ні — нижче останні дані" // uk-only: файл для Claude
    }

    /** Рядків журналу зв'язку у файлі. */
    const val LOG_LINES = 150

    /** Додати кадр [r]: лишаються [PER_TYPE] найсвіжіших кожного типу, загалом не більше [MAX_FRAMES]. */
    fun keep(list: List<Raw>, r: Raw): List<Raw> {
        val all = list + r
        val byType = all.groupBy { it.board to it.type }.values.flatMap { it.sortedBy { x -> x.at }.takeLast(PER_TYPE) }
        return byType.sortedBy { it.at }.takeLast(MAX_FRAMES)
    }

    /** Затерти в кадрі те, що не для чужих очей (див. опис об'єкта). Кадр не міняється — повертається копія. */
    fun redact(r: Raw): Raw {
        if (r.board != JK || r.type != Jk.TYPE_DEVICE) return r
        val b = r.bytes.copyOf()
        for (i in b.indices) if (i in 62 until 78 || i >= 86) b[i] = 0
        return r.copy(bytes = b)
    }

    /** Байти рядками по 16 з відступом: «000: aa 55 90 eb …». */
    fun hexDump(b: ByteArray): String = b.toList().chunked(16).mapIndexed { i, row ->
        "%03d: ".format(Locale.ROOT, i * 16) + row.joinToString(" ") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }.joinToString("\n")

    /** Самоперевірки знімка — що можна сказати без батареї поруч. Порожньо — нема знімка. */
    fun checks(s: BatterySnapshot?): List<String> {
        if (s == null) return emptyList()
        val out = ArrayList<String>()
        if (s.cellsMv.isNotEmpty() && s.voltage > 0) {
            val sum = s.cellsMv.sum() / 1000.0
            val diff = abs(sum - s.voltage) / s.voltage * 100
            val ok = diff < 3.0
            out += (if (ok) "OK  " else "!!  ") + "сума комірок %.3f В vs напруга %.2f В — розбіжність %.1f %%".format(Locale.ROOT, sum, s.voltage, diff) // uk-only: файл для Claude
        }
        if (s.cellCount > 0 && s.cellsMv.size != s.cellCount) {
            out += "!!  комірок у кадрі ${s.cellsMv.size}, а плата каже ${s.cellCount}" // uk-only: файл для Claude
        }
        if (s.fullAh > 0) {
            val soc = s.remainAh / s.fullAh * 100
            val ok = abs(soc - s.soc) <= 3
            out += (if (ok) "OK  " else "??  ") + "залишок/повна = %.1f %% vs заряд %d %%".format(Locale.ROOT, soc, s.soc) // uk-only: файл для Claude
        }
        s.cellsMv.filter { it < 2000 || it > 4300 }.takeIf { it.isNotEmpty() }?.let {
            out += "!!  дивні напруги комірок: ${it.joinToString()} мВ" // uk-only: файл для Claude
        }
        s.temperatures.filter { it < -40 || it > 100 }.takeIf { it.isNotEmpty() }?.let {
            out += "!!  дивні температури: ${it.joinToString()} °C" // uk-only: файл для Claude
        }
        return out
    }

    /** Що подати в [report]. Без Android: версії й назви — готовими рядками. */
    data class Input(
        val now: Long,
        val app: String,
        val android: String,
        val battery: String,
        val board: String,
        val snapshot: BatterySnapshot?,
        val snapshotAt: Long?,
        val frames: List<Raw>,
        val log: List<String>,
        val requestedBy: String,
        val fresh: Fresh,
    )

    /**
     * Текст файлу. Мова — українська навмисно: читає його Claude і faric, а не користувач застосунку (як журнал CSV).
     * Нічого з цього не впливає на роботу застосунку.
     */
    fun report(i: Input, tz: TimeZone = TimeZone.getDefault()): String = buildString { // uk-only: файл для Claude
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = tz }
        fun time(ms: Long) = t.format(Date(ms))
        appendLine("# Aolithium Live — дані для перевірки (T64)") // uk-only: файл для Claude
        appendLine("Складено: ${time(i.now)} · застосунок ${i.app} · Android ${i.android}") // uk-only: файл для Claude
        appendLine("Батарея: ${i.battery} · плата ${i.board}") // uk-only: файл для Claude
        appendLine("Попросив: ${i.requestedBy} · свіже читання: ${freshText(i.fresh)}") // uk-only: файл для Claude
        appendLine("Паролі плати й серійний номер у кадрах затерто нулями.") // uk-only: файл для Claude
        appendLine()
        appendLine("## Що порахував застосунок") // uk-only: файл для Claude
        val s = i.snapshot
        if (s == null) {
            appendLine("Знімка ще нема (з часу запуску застосунку батарею не прочитано).") // uk-only: файл для Claude
        } else {
            i.snapshotAt?.let { appendLine("Знімок від ${time(it)}") } // uk-only: файл для Claude
            appendLine("Напруга %.2f В · струм %.2f А · потужність %.0f Вт · стан %s".format(Locale.ROOT, s.voltage, s.current, s.power, s.flow.name)) // uk-only: файл для Claude
            appendLine("Заряд ${s.soc} %% · залишок %.2f з %.2f А·год · циклів ${s.cycles}".format(Locale.ROOT, s.remainAh, s.fullAh)) // uk-only: файл для Claude
            appendLine("Комірок ${s.cellsMv.size} (плата: ${s.cellCount}): ${s.cellsMv.joinToString(" ")} мВ") // uk-only: файл для Claude
            s.deltaMv?.let { appendLine("Різниця $it мВ · найнижча №${(s.minCell ?: 0) + 1} · найвища №${(s.maxCell ?: 0) + 1}") } // uk-only: файл для Claude
            if (s.balancingCells.isNotEmpty()) appendLine("Вирівнюються: ${s.balancingCells.joinToString { "№${it + 1}" }}") // uk-only: файл для Claude
            appendLine("Температури: ${s.temperatures.joinToString(" ") { "%.1f".format(Locale.ROOT, it) }} °C") // uk-only: файл для Claude
            appendLine("Ключі: заряд ${if (s.chargeOn) "увімк" else "ВИМК"} · розряд ${if (s.dischargeOn) "увімк" else "ВИМК"}") // uk-only: файл для Claude
            val prot = s.protections.map { it.name } + s.unknownProtectionBits.map { "bit$it" }
            appendLine("Захисти: ${if (prot.isEmpty()) "нема" else prot.joinToString()}") // uk-only: файл для Claude
            listOfNotNull(s.model?.let { "модель $it" }, s.firmware?.let { "програма $it" }, s.made?.let { "зроблено $it" }) // uk-only: файл для Claude
                .takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
            s.errorCounts?.takeIf { it.isNotEmpty() }?.let { e -> appendLine("Лічильники помилок: ${e.joinToString { "${it.first}=${it.second}" }}") } // uk-only: файл для Claude
            s.jk?.let { appendLine("JK: $it") }
        }
        appendLine()
        appendLine("## Самоперевірки") // uk-only: файл для Claude
        val c = checks(s)
        if (c.isEmpty()) appendLine("— (нема знімка)") else c.forEach { appendLine(it) } // uk-only: файл для Claude
        appendLine()
        appendLine("## Відповіді плати (${i.frames.size})") // uk-only: файл для Claude
        if (i.frames.isEmpty()) appendLine("Кадрів ще нема.") // uk-only: файл для Claude
        for (r in i.frames.map { redact(it) }) {
            val what = if (r.board == JK) "JK кадр 0x%02x".format(Locale.ROOT, r.type) // uk-only: файл для Claude
                else "JBD відповідь 0x%02x, стан 0x%02x (лише дані)".format(Locale.ROOT, r.type, r.status) // uk-only: файл для Claude
            appendLine("### ${time(r.at)} · $what · ${r.bytes.size} байт") // uk-only: файл для Claude
            appendLine(hexDump(r.bytes))
        }
        appendLine()
        appendLine("## Журнал зв'язку (останні ${i.log.size}, старіші зверху)") // uk-only: файл для Claude
        i.log.forEach { appendLine(it) }
    }

    /** Ім'я файлу: «aolithium-diag-20261022-0105.txt» — лише наші символи. */
    fun fileName(now: Long, tz: TimeZone = TimeZone.getDefault()): String =
        "aolithium-diag-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).apply { timeZone = tz }.format(Date(now)) + ".txt"
}

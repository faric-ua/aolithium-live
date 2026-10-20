package ua.faric.aolithium.core

/**
 * T48: вкладений JSON (`JsonTree`; плаский `MiniJson` — у DriveUpdate) — для відповідей Telegram (вкладені об'єкти й списки). `DriveUpdate.parseFlat` вміє лише
 * плаский, а org.json з Android у JVM-тестах — лише заглушка, бібліотек застосунок не має (правило проєкту).
 *
 * Розбір: об'єкт → `Map<String, Any?>`, список → `List<Any?>`, рядок, ціле → `Long`, дробове → `Double`,
 * true/false, null. Зіпсоване — [IllegalArgumentException].
 * Запис: [str] — рядок у лапках з екрануванням; [write] — Map / List / String / число / Boolean / null.
 */
object JsonTree {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("json: trailing data")
        return v
    }

    /** Безпечно: зіпсоване — null. */
    fun parseOrNull(text: String?): Any? = try {
        if (text == null) null else parse(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> str(k.toString()) + ":" + write(x) }
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> str(v.toString())
    }

    // ---------- зручні доступи ----------

    fun obj(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let { m ->
        @Suppress("UNCHECKED_CAST")
        m as Map<String, Any?>
    }

    fun list(v: Any?): List<Any?> = (v as? List<*>) ?: emptyList()

    fun long(v: Any?): Long? = when (v) {
        is Long -> v
        is Double -> v.toLong()
        else -> null
    }

    private const val MAX_DEPTH = 40

    private class Parser(val s: String) {
        var i = 0

        fun fail(): Nothing = throw IllegalArgumentException("json @ $i")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun peek(): Char = if (i < s.length) s[i] else fail()

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail()
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                if (peek() != '"') fail()
                val k = string()
                ws()
                if (peek() != ':') fail()
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail()
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws()
                l += value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail()
                }
            }
        }

        fun string(): String {
            i++ // "
            val b = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail()
                                b.append(code.toChar()) // пари сурогатів (емодзі) складаються самі
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i] in "0123456789.eE+-") i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") fail()
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDoubleOrNull() ?: fail()
            else t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail()
        }
    }
}

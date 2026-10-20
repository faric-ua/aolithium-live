package ua.faric.aolithium.core

import java.security.MessageDigest
import java.util.Locale

/**
 * T37 (faric 20.10): оновлення застосунку з Google Диска автора — перенесено з Track Catcher (`core/DriveUpdate.kt`
 * там, з уроками T46, T47, T54, T56, 0.1.45, 0.1.48), а туди — з Dorama T88.
 *
 * Телефон автора один раз створює на своєму Диску два файли (вікном Android, вхід у Google не
 * потрібен) і далі завжди пише в ті самі файли — посилання не змінюються: APK і маленький
 * «файл версії». Обидва відкриті «Усім, хто має посилання». Будь-який телефон читає файл версії
 * без входу в Google, порівнює versionCode, завантажує APK, перевіряє SHA-256, пакет, версію й
 * ключ підпису — і лише тоді відкриває вікно Android «Оновити цей застосунок?».
 *
 * Тут — лише правила без Android (посилання, файл версії, рішення), щоб їх покривали тести.
 * JSON — свій маленький: у JVM-тестах org.json з Android — лише заглушка, а бібліотек застосунок не має.
 * Батареї це не стосується: з телефона йде лише запит файлів з Диска автора, дані батареї нікуди не йдуть.
 */
object DriveUpdate {
    const val FORMAT = "aolithium-live-update/1"
    const val FORMAT_PLACEHOLDER = "aolithium-live-update/0"
    const val APK_FILE_NAME = "Aolithium Live.apk"
    const val VERSION_FILE_NAME = "aolithium-live-onovlennia.json"

    /**
     * Вбудоване посилання на файл версії на Диску автора (ID файлу `aolithium-live-onovlennia.json`) — інші телефони
     * перевіряють без вставляння. Файли створив Claude 20.10.2026 (faric: «створи файли на Диску, як у інших додатків»)
     * у тій самій структурі, що в Track Catcher / Audio Converter / Renault Docs: **AppsAndroidSync › AolithiumLive › App**.
     *
     * Урок Track Catcher T47 / T54: вбудоване посилання — обіцянка старим версіям. Файл, на який воно вказує, не можна
     * кидати чи створювати заново; якщо автор вибере інший файл версії — вікно публікації жовто попередить.
     */
    const val BUILT_IN_MANIFEST_ID = "1s6SVbZuXEszmYC71S9avvTW9ilhsaOGB"

    /**
     * Вбудоване посилання на `Aolithium Live.apk` у тій самій папці — кроки 3–4 автора більше не потрібні (0.4.6).
     * Заглушку Claude faric замінив справжнім файлом, публікуючи 0.4.5 (20.10 16:39) — тут ID саме того файлу.
     */
    const val BUILT_IN_APK_ID = "1IYhxxrMWtVPeRoVzqKIA--qJWRu6ECzm"

    /** Де лежать ці файли — для підказки автору на кроках 1–2. */
    const val BUILT_IN_FOLDER = "AppsAndroidSync › AolithiumLive › App"

    /** Файли вже є на Диску: автор їх лише вибирає (кроки 1–2), посилання вставляти не треба. */
    val filesBuiltIn: Boolean get() = BUILT_IN_MANIFEST_ID.isNotBlank() && BUILT_IN_APK_ID.isNotBlank()

    /**
     * Посилання вставили не на файл версії (Track Catcher T46: faric вставив посилання на сам APK —
     * «Файл на Диску завеликий (6,1 МБ)»). Таке посилання не зберігається, а вже збережене — забувається.
     */
    class WrongFileException(message: String) : IllegalArgumentException(message)

    val LINK_HINT: String
        get() = tr(
            "Потрібне посилання саме на маленький файл версії «$VERSION_FILE_NAME», не на APK. " +
                "Найпростіше: на телефоні автора «Опублікувати цю версію на Диск» → «Поділитися посиланням на оновлення».",
            "The link must point to the small version file \"$VERSION_FILE_NAME\", not to the APK. " +
                "Easiest: on the author's phone, \"Publish this version to Drive\" → \"Share the update link\".",
        )

    fun linkIsApkMessage(size: Long): String {
        val mb = if (size > 0) ", ${formatMb(size)}" else ""
        return tr(
            "За цим посиланням — сам застосунок (APK$mb), а не файл версії.\n\n$LINK_HINT",
            "This link leads to the app itself (APK$mb), not to the version file.\n\n$LINK_HINT",
        )
    }

    /** З прочитаних файлів версії — з найбільшим versionCode; при рівних — перший (збережене раніше за вбудоване). */
    fun newest(read: List<Pair<String, Manifest>>): Pair<String, Manifest>? =
        read.fold(null as Pair<String, Manifest>?) { best, cur -> if (best == null || cur.second.versionCode > best.second.versionCode) cur else best }

    /**
     * Які файли версії пробувати: вставлене зараз — лише його; інакше збережене й вбудоване в застосунок
     * (Track Catcher T56: читаються всі, береться найновіша версія).
     */
    fun manifestCandidates(pasted: String?, saved: String?, builtIn: String): List<String> =
        if (!pasted.isNullOrBlank()) listOf(pasted)
        else listOfNotNull(saved?.takeIf { it.isNotBlank() }, builtIn.takeIf { it.isNotBlank() }).distinct()

    private val BARE_ID = Regex("^[A-Za-z0-9_-]{20,80}$")
    private val PATH_ID = Regex("/(?:file/)?d/([A-Za-z0-9_-]{20,80})")
    private val QUERY_ID = Regex("[?&]id=([A-Za-z0-9_-]{20,80})")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /**
     * Те, що дає «Копіювати посилання» в Google Диску (`https://drive.google.com/file/d/ID/view?usp=sharing`),
     * старі форми `open?id=ID` / `uc?id=ID`, або сам ID. Посилання на папку — null навмисно:
     * папку без входу в Google не прочитати.
     */
    fun fileIdFromLink(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (BARE_ID.matches(value)) return value
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.contains("google.com")) return null
        if (lower.contains("/folders/")) return null
        PATH_ID.find(value)?.let { return it.groupValues[1] }
        QUERY_ID.find(value)?.let { return it.groupValues[1] }
        return null
    }

    /** Пряме завантаження відкритого файлу. `confirm=t` — без сторінки «не можу перевірити на віруси». */
    fun downloadUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    /** Посилання для людей (пишеться у файл версії). */
    fun viewUrl(fileId: String): String = "https://drive.google.com/file/d/$fileId/view"

    /** Код, з яким варто спробувати ще раз: тимчасовий збій Google (5xx) чи «забагато запитів» (429). Track Catcher 0.1.45. */
    fun retryable(code: Int): Boolean = code == 429 || code in 500..599

    /**
     * Після «Опублікувати зараз» (Track Catcher 0.1.48, faric 20.10 15:06: «опублікував, а її там нема — не перший раз»):
     * запис через Google Диск на телефоні лише ставить файл у чергу — вивантажує застосунок Диска у фоні, коли захоче.
     * Тому перевіряємо, що бачать інші телефони: файл версії з інтернету — саме наш (номер збірки, SHA і розмір).
     */
    fun manifestIsOurs(m: Manifest?, versionCode: Long, sha256: String, size: Long): Boolean =
        m != null && m.versionCode == versionCode && m.apkSha256.equals(sha256, ignoreCase = true) && m.apkSize == size

    /** Чекаємо вивантаження: перевірки через 15 с, далі що 30 с, разом до ~6 хв. */
    val PUBLISH_CHECKS_MS = longArrayOf(15_000, 30_000, 30_000, 30_000, 30_000, 45_000, 60_000, 60_000, 60_000)

    /** Тиха перевірка інших телефонів — не частіше за раз на добу. */
    const val AUTO_CHECK_MS = 24L * 60 * 60 * 1000

    /** Автопублікація не вдалась — наступна спроба тієї самої версії не раніше, ніж за 30 хв (не на кожне відкриття). */
    const val AUTO_PUBLISH_RETRY_MS = 30L * 60 * 1000

    /**
     * Тиха перевірка при відкритті: увімкнена, є звідки брати, і з минулої минула доба.
     * Годинник телефона перевели назад (now < last) — теж пора, інакше перевірка застрягла б надовго.
     */
    fun autoCheckDue(on: Boolean, hasLink: Boolean, lastAt: Long, now: Long): Boolean =
        on && hasLink && (now < lastAt || now - lastAt >= AUTO_CHECK_MS)

    /**
     * T37 (faric 20.10: «автоматичне вивантаження й автоматична перевірка»): телефон автора сам публікує, коли
     * відкрили застосунок, а встановлена версія новіша за ту, що він уже опублікував і перевірив.
     * [ready] — файли й посилання налаштовані (кроки 1–4). Невдала спроба тієї самої версії — не частіше за
     * [AUTO_PUBLISH_RETRY_MS]. Що на Диску вже така сама чи новіша (з іншого телефона) — перевіряється перед записом.
     */
    fun autoPublishDue(
        on: Boolean,
        ready: Boolean,
        installedCode: Long,
        publishedCode: Long,
        triedCode: Long,
        triedAt: Long,
        now: Long,
    ): Boolean {
        if (!on || !ready) return false
        if (installedCode <= publishedCode) return false
        if (triedCode == installedCode && now >= triedAt && now - triedAt < AUTO_PUBLISH_RETRY_MS) return false
        return true
    }

    /** Закритий чи видалений файл Диск віддає HTML-сторінкою входу/помилки, а не файлом. */
    fun looksLikeHtml(contentType: String?, head: ByteArray?, length: Int): Boolean {
        if (contentType != null && contentType.lowercase(Locale.ROOT).contains("text/html")) return true
        if (head == null) return false
        val end = minOf(length, head.size)
        var start = 0
        while (start < end && head[start].toInt().toChar().isWhitespace()) start++
        if (start >= end) return false
        val prefix = String(head, start, minOf(15, end - start), Charsets.US_ASCII).lowercase(Locale.ROOT)
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html")
    }

    /** APK — це ZIP: починається з «PK». */
    fun looksLikeApk(head: ByteArray?, length: Int): Boolean =
        head != null && length >= 2 && head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    fun sha256Hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** «6,1 МБ» / «6.1 MB» — мовою застосунку (T12). */
    fun formatMb(bytes: Long): String = Fmt.num(bytes / (1024.0 * 1024.0), 1) + " " + tr("МБ", "MB")

    /** Файл версії, який пише телефон автора. */
    data class Manifest(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val apkSha256: String,
        val apkSize: Long,
        val apkId: String,
        val publishedAt: Long,
    ) {
        fun toJson(): String = buildString {
            append("{\n")
            val fields = listOf(
                "format" to FORMAT,
                "package" to packageName,
                "versionCode" to versionCode,
                "versionName" to versionName,
                "apkSha256" to apkSha256,
                "apkSize" to apkSize,
                "apkId" to apkId,
                "apkLink" to viewUrl(apkId),
                "publishedAt" to publishedAt,
            )
            fields.forEachIndexed { i, (k, v) ->
                append("  ").append(MiniJson.quote(k)).append(": ")
                append(if (v is String) MiniJson.quote(v) else v.toString())
                append(if (i == fields.lastIndex) "\n" else ",\n")
            }
            append("}\n")
        }

        companion object {
            /** Читає файл версії. Не наш чи неповний — [IllegalArgumentException] з реченням для екрана. */
            fun parse(text: String?): Manifest {
                val json = try {
                    MiniJson.parseFlat(text.orEmpty())
                } catch (e: IllegalArgumentException) {
                    throw WrongFileException(tr(
                        "За цим посиланням — не файл версії Aolithium Live.\n\n$LINK_HINT",
                        "This link does not lead to an Aolithium Live version file.\n\n$LINK_HINT",
                    ))
                }
                if (json["format"] != FORMAT) {
                    if (json["format"] == FORMAT_PLACEHOLDER) {
                        throw IllegalArgumentException(tr("Автор ще не опублікував жодної версії.", "The author has not published any version yet."))
                    }
                    throw WrongFileException(tr(
                        "Це не файл оновлень Aolithium Live (інший формат).\n\n$LINK_HINT",
                        "This is not an Aolithium Live update file (other format).\n\n$LINK_HINT",
                    ))
                }
                val pkg = json["package"] as? String ?: ""
                val code = (json["versionCode"] as? Long) ?: -1L
                val name = json["versionName"] as? String ?: ""
                val sha = (json["apkSha256"] as? String ?: "").lowercase(Locale.ROOT)
                val size = (json["apkSize"] as? Long) ?: -1L
                val apkId = json["apkId"] as? String ?: ""
                if (pkg.isEmpty() || code <= 0 || !SHA256.matches(sha) || size <= 0 || !BARE_ID.matches(apkId)) {
                    throw IllegalArgumentException(tr(
                        "Файл версії на Диску неповний — автор має опублікувати версію ще раз.",
                        "The version file on Drive is incomplete. The author has to publish the version again.",
                    ))
                }
                return Manifest(pkg, code, name.ifEmpty { code.toString() }, sha, size, apkId, (json["publishedAt"] as? Long) ?: 0L)
            }
        }
    }

    /** Порожній файл версії, поки автор нічого не опублікував (щоб у Диску можна було скопіювати посилання). */
    const val PLACEHOLDER = "{\"format\": \"$FORMAT_PLACEHOLDER\", \"note\": \"not published yet\"}\n"

    enum class Verdict { NEWER, SAME, OLDER, OTHER_PACKAGE }

    fun compare(m: Manifest, installedPackage: String, installedCode: Long): Verdict = when {
        m.packageName != installedPackage -> Verdict.OTHER_PACKAGE
        m.versionCode > installedCode -> Verdict.NEWER
        m.versionCode == installedCode -> Verdict.SAME
        else -> Verdict.OLDER
    }

    /**
     * Перевірка завантаженого APK проти файлу версії й установленого застосунку (5 перевірок: розмір і SHA-256,
     * APK читається, той самий пакет, та сама версія, що у файлі версії, той самий ключ підпису).
     * null — усе збіглося; інакше речення для екрана.
     */
    fun checkDownloaded(
        m: Manifest,
        sha256: String,
        size: Long,
        apkPackage: String?,
        apkVersionCode: Long,
        apkCertSha256: String?,
        installedCertSha256: String?,
    ): String? {
        if (size != m.apkSize || !m.apkSha256.equals(sha256, ignoreCase = true)) {
            return tr(
                "Завантажений файл не збігається з файлом версії (контрольна сума). " +
                    "Можливо, автор саме зараз публікує нову версію — спробуй через кілька хвилин.",
                "The downloaded file does not match the version file (checksum). " +
                    "Maybe the author is publishing a new version right now. Try again in a few minutes.",
            )
        }
        if (apkPackage == null) return tr("Android не зміг прочитати завантажений APK.", "Android could not read the downloaded APK.")
        if (apkPackage != m.packageName) {
            return tr("Завантажений APK — для іншого застосунку ($apkPackage). Не встановлюю.",
                "The downloaded APK is for another app ($apkPackage). Not installing.")
        }
        if (apkVersionCode != m.versionCode) {
            return tr("Версія всередині APK ($apkVersionCode) не та, що у файлі версії (${m.versionCode}). Не встановлюю.",
                "The version inside the APK ($apkVersionCode) is not the one in the version file (${m.versionCode}). Not installing.")
        }
        if (apkCertSha256 != null && installedCertSha256 != null && !apkCertSha256.equals(installedCertSha256, ignoreCase = true)) {
            return tr("APK підписаний іншим ключем, ніж встановлений застосунок. Не встановлюю — це може бути чужий файл.",
                "The APK is signed with a different key than the installed app. Not installing: it may be someone else's file.")
        }
        return null
    }
}

/** Плаский JSON-об'єкт: рядки, цілі числа, true/false/null. Вкладене — помилка. (Копія з Track Catcher.) */
internal object MiniJson {
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun parseFlat(text: String): Map<String, Any?> {
        val p = Parser(text)
        p.ws()
        p.expect('{')
        val out = LinkedHashMap<String, Any?>()
        p.ws()
        if (p.peek() == '}') {
            p.i++
        } else {
            while (true) {
                p.ws()
                val key = p.string()
                p.ws()
                p.expect(':')
                p.ws()
                out[key] = p.value()
                p.ws()
                when (p.next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw IllegalArgumentException("json")
                }
            }
        }
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("json")
        return out
    }

    private class Parser(val s: String) {
        var i = 0
        fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("json")
        fun next(): Char = peek().also { i++ }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { if (next() != c) throw IllegalArgumentException("json") }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw IllegalArgumentException("json")
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("json"))
                            i += 4
                        }
                        else -> throw IllegalArgumentException("json: \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun value(): Any? {
            val c = peek()
            return when {
                c == '"' -> string()
                c == '-' || c.isDigit() -> {
                    val start = i
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                    val raw = s.substring(start, i)
                    raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException("json")
                }
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> throw IllegalArgumentException("json")
            }
        }
    }
}

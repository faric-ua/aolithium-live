package ua.faric.aolithium.core

/**
 * T48: що приходить від Telegram і що туди йде — без Android і без мережі (мережа — у теці `guard`).
 * Лише те, що потрібно сторожу: повідомлення людей боту, натискання кнопок під повідомленням, ім'я бота, помилки.
 */
object Telegram {
    const val HOST = "api.telegram.org"

    /** Ліміт Telegram на одне повідомлення — 4096 символів; беремо із запасом. */
    const val MAX_TEXT = 3900

    /** Адреса методу. Токен — лише в пам'яті телефона-сторожа, ніде не пишемо в журнал (див. [mask]). */
    fun url(token: String, method: String): String = "https://$HOST/bot$token/$method"

    /**
     * Токен від @BotFather: «123456789:AA…» (цифри, двокрапка, 30+ знаків). Перевіряємо форму, щоб одразу сказати,
     * що вставили щось не те (зайвий пробіл, неповний шматок), а не чекати помилки від Telegram.
     */
    fun tokenLooksRight(token: String): Boolean = Regex("""\d{5,12}:[A-Za-z0-9_-]{30,60}""").matches(token)

    /** Прибрати пробіли й лапки, які чіпляються під час копіювання. */
    fun cleanToken(raw: String): String = raw.trim().trim('"', '\'', '«', '»', '`').replace(" ", "").replace("\n", "")

    /** Для журналу: «123456789:AA…xyz» — щоб навіть у журналі зв'язку токена не було. */
    fun mask(token: String): String {
        val id = token.substringBefore(':')
        return if (token.length < 12) "…" else "$id:…${token.takeLast(3)}"
    }

    /** Одне оновлення від Telegram: текст людини боту або натискання кнопки. */
    data class Update(
        val updateId: Long,
        val chatId: Long,
        val name: String,
        /** Приватний чат з ботом (у групі команди теж працюють, але відповідаємо в ту саму групу). */
        val private: Boolean,
        val text: String? = null,
        val callbackId: String? = null,
        val callbackData: String? = null,
        val messageId: Long? = null,
        /** T58: службове «додали учасників» — їхні імена «@бот» (щоб упізнати, що додали саме нас). */
        val added: List<String> = emptyList(),
        /** T58: службове «групу створено» — бот був серед перших учасників. */
        val groupCreated: Boolean = false,
    )

    /** Відповідь Telegram: [ok] і що прийшло, або [error] людською мовою, [code] — номер помилки (401, 403, 409…). */
    data class Reply(
        val ok: Boolean,
        val result: Any?,
        val code: Int = 0,
        val error: String = "",
        /** T52: група стала супергрупою — у неї новий номер чату; повідомлення треба слати туди. */
        val migrateTo: Long? = null,
    )

    fun reply(text: String?): Reply {
        val o = JsonTree.obj(JsonTree.parseOrNull(text))
            ?: return Reply(false, null, 0, tr("незрозуміла відповідь", "unreadable answer"))
        if (o["ok"] == true) return Reply(true, o["result"])
        val code = JsonTree.long(o["error_code"])?.toInt() ?: 0
        val migrate = JsonTree.long(JsonTree.obj(o["parameters"])?.get("migrate_to_chat_id"))
        return Reply(false, null, code, o["description"] as? String ?: "", migrate)
    }

    /** Ім'я бота з `getMe` — «@aolithium_home_bot». */
    fun botName(r: Reply): String? = JsonTree.obj(r.result)?.get("username")?.let { "@$it" }

    fun updates(r: Reply): List<Update> = JsonTree.list(r.result).mapNotNull { u -> update(JsonTree.obj(u)) }

    private fun person(from: Map<String, Any?>?): String {
        if (from == null) return "?"
        val first = from["first_name"] as? String ?: ""
        val last = from["last_name"] as? String ?: ""
        val user = from["username"] as? String
        val full = "$first $last".trim()
        return when {
            full.isNotEmpty() -> full
            user != null -> "@$user"
            else -> "?"
        }
    }

    /** T52: у групі учасник — уся група, тож і ім'я — назва групи («Батареї родини»), а не того, хто натиснув. */
    private fun who(chat: Map<String, Any?>?, from: Map<String, Any?>?): String {
        val type = chat?.get("type")
        val title = chat?.get("title") as? String
        return if ((type == "group" || type == "supergroup") && !title.isNullOrBlank()) title else person(from)
    }

    private fun update(u: Map<String, Any?>?): Update? {
        if (u == null) return null
        val id = JsonTree.long(u["update_id"]) ?: return null
        JsonTree.obj(u["message"])?.let { m ->
            val chat = JsonTree.obj(m["chat"]) ?: return Update(id, 0, "?", false)
            val chatId = JsonTree.long(chat["id"]) ?: return Update(id, 0, "?", false)
            val added = JsonTree.list(m["new_chat_members"]).mapNotNull { x -> (JsonTree.obj(x)?.get("username") as? String)?.let { "@$it" } }
            val created = m["group_chat_created"] == true || m["supergroup_chat_created"] == true
            return Update(id, chatId, who(chat, JsonTree.obj(m["from"])), chat["type"] == "private", text = m["text"] as? String,
                added = added, groupCreated = created)
        }
        JsonTree.obj(u["callback_query"])?.let { q ->
            val msg = JsonTree.obj(q["message"])
            val chatId = JsonTree.long(JsonTree.obj(msg?.get("chat"))?.get("id")) ?: return Update(id, 0, "?", false)
            val chat = JsonTree.obj(msg?.get("chat"))
            return Update(
                id, chatId, who(chat, JsonTree.obj(q["from"])), chat?.get("type") == "private",
                callbackId = q["id"] as? String, callbackData = q["data"] as? String,
                messageId = JsonTree.long(msg?.get("message_id")),
            )
        }
        // Інше (людина заблокувала бота, змінила повідомлення…) — лише пропустити, щоб не читати знову.
        return Update(id, 0, "?", false)
    }

    /** Кнопки під повідомленням: ряди кнопок «напис → дані». */
    data class Button(val text: String, val data: String)

    fun keyboardJson(rows: List<List<Button>>): String = JsonTree.write(
        mapOf("inline_keyboard" to rows.map { r -> r.map { b -> mapOf("text" to b.text, "callback_data" to b.data) } }),
    )

    fun sendBody(chatId: Long, text: String, keyboard: List<List<Button>>? = null): String {
        val m = linkedMapOf<String, Any?>("chat_id" to chatId, "text" to text.take(MAX_TEXT), "disable_web_page_preview" to true)
        if (keyboard != null) m["reply_markup"] = JsonTree.parse(keyboardJson(keyboard))
        return JsonTree.write(m)
    }

    fun editBody(chatId: Long, messageId: Long, text: String, keyboard: List<List<Button>>?): String {
        val m = linkedMapOf<String, Any?>("chat_id" to chatId, "message_id" to messageId, "text" to text.take(MAX_TEXT))
        if (keyboard != null) m["reply_markup"] = JsonTree.parse(keyboardJson(keyboard))
        return JsonTree.write(m)
    }

    /** T59: поля `sendPhoto` (сама картинка — окремо, файлом). Підпис — не довший за ліміт Telegram. */
    fun photoFields(chatId: Long, caption: String, keyboard: List<List<Button>>? = null): List<Pair<String, String>> {
        val f = arrayListOf("chat_id" to "$chatId")
        if (caption.isNotEmpty()) f += "caption" to caption.take(GuardPicture.CAPTION_MAX)
        if (keyboard != null) f += "reply_markup" to keyboardJson(keyboard)
        return f
    }

    /**
     * T59: тіло `multipart/form-data` без бібліотек — текстові поля й один файл PNG. [boundary] — рядок, якого нема
     * в даних (у полях — лише текст, а PNG перевіряти не треба: межу шукають лише з «\r\n--» на початку рядка).
     */
    fun multipart(boundary: String, fields: List<Pair<String, String>>, fileField: String, fileName: String, png: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n")
            w(v)
            w("\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n")
        w("Content-Type: image/png\r\n\r\n")
        out.write(png)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    fun answerBody(callbackId: String, text: String): String =
        JsonTree.write(mapOf("callback_query_id" to callbackId, "text" to text.take(190)))

    /** Довге опитування: Telegram тримає запит до [timeoutS] с, поки не прийде щось нове. */
    fun updatesBody(offset: Long, timeoutS: Int): String =
        JsonTree.write(mapOf("offset" to offset, "timeout" to timeoutS, "allowed_updates" to listOf("message", "callback_query")))

    /** Меню команд у Telegram (кнопка «Меню» поруч з полем вводу). */
    fun commandsBody(commands: List<Pair<String, String>>): String =
        JsonTree.write(mapOf("commands" to commands.map { (c, d) -> mapOf("command" to c, "description" to d) }))

    /** Людина заблокувала бота або видалила чат — слати йому більше нема сенсу. */
    fun goneForGood(r: Reply): Boolean = r.code == 403 || (r.code == 400 && r.error.contains("chat not found", ignoreCase = true))

    /** Що сказати власнику про помилку Telegram. */
    fun explain(r: Reply): String = when (r.code) {
        401, 404 -> tr("Telegram не впізнав токен — перевір, чи скопійовано повністю.", "Telegram did not recognise the token — check it was copied in full.")
        409 -> tr("Цей бот уже працює на іншому телефоні-сторожі. Один бот — один сторож.",
            "This bot already runs on another guard phone. One bot — one guard.")
        429 -> tr("Telegram просить слати рідше — повідомлення підуть трохи пізніше.", "Telegram asks to send less often — messages will go a bit later.")
        0 -> if (r.error.isNotEmpty()) r.error else tr("нема зв'язку з Telegram", "no connection to Telegram")
        else -> tr("Telegram відповів помилкою ${r.code}", "Telegram answered with error ${r.code}") +
            (if (r.error.isNotEmpty()) ": ${r.error}" else "")
    }
}

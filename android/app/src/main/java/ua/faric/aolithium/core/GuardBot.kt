package ua.faric.aolithium.core

/**
 * T48: розмова людей з ботом сторожа (faric 21.10 01:53: «можна додати декілька учасників… кожний учасник може
 * налаштувати, які сповіщення він хоче отримувати»). Без Android і без мережі — вхід: що написала людина,
 * вихід: що відповісти і хто тепер учасник.
 *
 * Під'єднатись — лише з **кодом доступу** (його бачить власник на телефоні-сторожі й пересилає своїм): інакше
 * будь-хто, хто знайде бота в Telegram, читав би стан чужої батареї. 5 хибних кодів — година паузи.
 */
object GuardBot {
    const val MAX_MEMBERS = 20
    const val CODE_LEN = 6
    const val MAX_TRIES = 5
    const val TRIES_WINDOW_MS = 3_600_000L

    data class Member(val chatId: Long, val name: String, val kinds: Set<Guard.Kind> = Guard.Kind.ALL, val since: Long = 0L) {
        fun wants(k: Guard.Kind) = k in kinds
    }

    /** Учасники — рядок для пам'яті телефона: по рядку на людину, поля через табуляцію. */
    fun encode(members: List<Member>): String = members.joinToString("\n") { m ->
        listOf("${m.chatId}", m.name.replace('\t', ' ').replace('\n', ' '), m.kinds.joinToString(",") { it.key }, "${m.since}").joinToString("\t")
    }

    fun decode(raw: String?): List<Member> = raw.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        val id = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val kinds = if (f.size > 2) f[2].split(',').mapNotNull { Guard.Kind.of(it) }.toSet() else Guard.Kind.ALL
        Member(id, f.getOrNull(1).orEmpty().ifBlank { "?" }, kinds, f.getOrNull(3)?.toLongOrNull() ?: 0L)
    }

    /** Новий код доступу з [random] (0 ≤ x < 1) — шість цифр, не з нуля, щоб не губився при копіюванні. */
    fun newCode(random: () -> Double): String = (100_000 + (random() * 900_000).toInt().coerceIn(0, 899_999)).toString()

    sealed class Action {
        data class Send(val chatId: Long, val text: String, val keyboard: List<List<Telegram.Button>>? = null) : Action()
        data class Edit(val chatId: Long, val messageId: Long, val text: String, val keyboard: List<List<Telegram.Button>>?) : Action()
        data class Answer(val callbackId: String, val text: String) : Action()
    }

    /** Спроби коду: chatId → (скільки, коли перша у вікні). Лише в пам'яті сторожа. */
    data class Tries(val count: Int, val since: Long)

    data class Input(
        val code: String,
        val members: List<Member>,
        /** «/status»: що зараз з батареями (готовий текст від `Guard.status`). */
        val status: () -> String,
        val now: Long,
        val tries: Map<Long, Tries> = emptyMap(),
    )

    data class Result(
        val members: List<Member>,
        val actions: List<Action>,
        val tries: Map<Long, Tries>,
        /** Для журналу зв'язку на телефоні-сторожі: «Олег під'єднався». null — нічого цікавого. */
        val note: String? = null,
    )

    val commands: List<Pair<String, String>>
        get() = listOf(
            "status" to tr("Що зараз з батареєю", "What the battery is doing now"),
            "settings" to tr("Що мені надсилати", "What to send me"),
            "stop" to tr("Більше не надсилати", "Stop sending"),
            "help" to tr("Як це працює", "How it works"),
        )

    fun handle(u: Telegram.Update, input: Input): Result {
        if (u.chatId == 0L) return Result(input.members, emptyList(), input.tries)
        val me = input.members.firstOrNull { it.chatId == u.chatId }
        u.callbackData?.let { return callback(u, it, me, input) }
        val raw = u.text?.trim() ?: return Result(input.members, emptyList(), input.tries)
        val cmd = raw.substringBefore(' ').substringBefore('@').lowercase()
        val arg = raw.substringAfter(' ', "").trim()
        val word = raw.lowercase()

        if (me == null) {
            val offered = when {
                cmd == "/start" && arg.isNotEmpty() -> arg
                raw.all { it.isDigit() || it == ' ' } && raw.any { it.isDigit() } -> raw
                else -> null
            }?.filter { it.isDigit() }
            if (offered == null) return say(input, u.chatId, hello())
            return join(u, offered, input)
        }
        return when {
            cmd == "/status" || word == "стан" || word == "status" -> say(input, u.chatId, input.status()) // uk-only: слово людини
            cmd == "/settings" || cmd == "/start" || word == "налаштування" -> // uk-only: слово людини
                Result(input.members, listOf(Action.Send(u.chatId, settingsText(me), keyboard(me))), input.tries)
            cmd == "/stop" -> {
                val left = input.members.filterNot { it.chatId == u.chatId }
                Result(left, listOf(Action.Send(u.chatId, tr(
                    "Добре, більше не надсилатиму. Повернутись — знову надішли код доступу.",
                    "OK, I will not send anything more. To come back, send the access code again.",
                ))), input.tries, tr("${me.name} відписався", "${me.name} unsubscribed"))
            }
            else -> say(input, u.chatId, help())
        }
    }

    private fun say(input: Input, chatId: Long, text: String) = Result(input.members, listOf(Action.Send(chatId, text)), input.tries)

    private fun join(u: Telegram.Update, offered: String, input: Input): Result {
        val t = input.tries[u.chatId]?.takeIf { input.now - it.since < TRIES_WINDOW_MS }
        if (t != null && t.count >= MAX_TRIES) {
            return say(input, u.chatId, tr("Забагато спроб. Спробуй за годину.", "Too many tries. Try again in an hour."))
        }
        if (input.code.length != CODE_LEN || offered != input.code) {
            val nt = input.tries + (u.chatId to Tries((t?.count ?: 0) + 1, t?.since ?: input.now))
            return Result(input.members, listOf(Action.Send(u.chatId, tr(
                "Код не підходить. Попроси в власника сторожа свіжий код (6 цифр).",
                "The code does not match. Ask the guard's owner for a fresh code (6 digits).",
            ))), nt, tr("хибний код від ${u.name}", "wrong code from ${u.name}"))
        }
        if (input.members.size >= MAX_MEMBERS) {
            return say(input, u.chatId, tr("У сторожа вже $MAX_MEMBERS учасників — більше не можна.", "The guard already has $MAX_MEMBERS members — no more allowed."))
        }
        val m = Member(u.chatId, u.name, Guard.Kind.ALL, input.now)
        val welcome = tr(
            "Вітаю, ${u.name}! Тепер я надсилатиму тобі новини батареї. Нижче — що саме; торкнись, щоб вимкнути зайве.",
            "Welcome, ${u.name}! I will now send you battery news. Below is what exactly; tap to turn off what you don't need.",
        )
        return Result(
            input.members + m,
            listOf(Action.Send(u.chatId, welcome), Action.Send(u.chatId, settingsText(m), keyboard(m))),
            input.tries - u.chatId,
            tr("${u.name} під'єднався", "${u.name} joined"),
        )
    }

    private fun callback(u: Telegram.Update, data: String, me: Member?, input: Input): Result {
        val id = u.callbackId ?: ""
        if (me == null) {
            return Result(input.members, listOf(Action.Answer(id, tr("Спершу надішли код доступу.", "Send the access code first."))), input.tries)
        }
        val msgId = u.messageId
        if (data == "done") {
            val acts = mutableListOf<Action>(Action.Answer(id, tr("Збережено", "Saved")))
            if (msgId != null) acts += Action.Edit(u.chatId, msgId, summary(me), null)
            return Result(input.members, acts, input.tries)
        }
        val kind = data.removePrefix("k:").let { Guard.Kind.of(it) }
            ?: return Result(input.members, listOf(Action.Answer(id, "")), input.tries)
        val updated = me.copy(kinds = if (me.wants(kind)) me.kinds - kind else me.kinds + kind)
        val members = input.members.map { if (it.chatId == me.chatId) updated else it }
        val acts = mutableListOf<Action>(Action.Answer(id, (if (updated.wants(kind)) tr("Увімкнено: ", "On: ") else tr("Вимкнено: ", "Off: ")) + kind.title))
        if (msgId != null) acts += Action.Edit(u.chatId, msgId, settingsText(updated), keyboard(updated))
        return Result(members, acts, input.tries)
    }

    fun hello(): String = tr(
        "Привіт! Це сторож домашньої батареї (Aolithium Live). Щоб отримувати сповіщення, надішли код доступу — 6 цифр, " +
            "його бачить власник сторожа в застосунку.",
        "Hi! This is a home battery guard (Aolithium Live). To get notifications, send the access code — 6 digits, " +
            "the guard's owner sees it in the app.",
    )

    fun help(): String = tr(
        "Я стежу за батареєю й пишу, коли щось сталося. Команди:\n/status — що зараз з батареєю\n/settings — що тобі надсилати\n" +
            "/stop — більше не надсилати\nЩодня зранку пишу «Сторож працює». Не прийшло — зі сторожем щось сталося.",
        "I watch the battery and write when something happens. Commands:\n/status — what the battery is doing now\n" +
            "/settings — what to send you\n/stop — stop sending\nEvery morning I write \"The guard is working\". If it does not come, " +
            "something happened to the guard.",
    )

    fun settingsText(m: Member): String = tr(
        "Що надсилати тобі (${m.kinds.size} з ${Guard.Kind.entries.size}). Торкнись, щоб увімкнути або вимкнути:",
        "What to send you (${m.kinds.size} of ${Guard.Kind.entries.size}). Tap to turn on or off:",
    )

    fun summary(m: Member): String = if (m.kinds.isEmpty()) tr(
        "Усе вимкнено — я нічого не надсилатиму. Змінити — /settings.",
        "Everything is off — I will send nothing. To change — /settings.",
    ) else tr("Надсилатиму: ", "I will send: ") + Guard.Kind.entries.filter { m.wants(it) }.joinToString(", ") { it.title } +
        tr(". Змінити — /settings.", ". To change — /settings.")

    /** Кнопки: 🔔 — увімкнено, 🔕 — вимкнено (не галочки — правило інтерфейсу). */
    fun keyboard(m: Member): List<List<Telegram.Button>> =
        Guard.Kind.entries.map { k ->
            listOf(Telegram.Button((if (m.wants(k)) "🔔 " else "🔕 ") + k.title, "k:${k.key}"))
        } + listOf(listOf(Telegram.Button(tr("Готово", "Done"), "done")))

    /** Кому слати це повідомлення. */
    fun recipients(members: List<Member>, kind: Guard.Kind): List<Long> = members.filter { it.wants(kind) }.map { it.chatId }
}

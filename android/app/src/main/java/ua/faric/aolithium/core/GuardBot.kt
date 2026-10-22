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

    data class Member(
        val chatId: Long,
        val name: String,
        val kinds: Set<Guard.Kind> = Guard.Kind.ALL,
        val since: Long = 0L,
        /** T59: який вигляд картинки слати цьому учаснику. */
        val look: GuardPicture.Look = GuardPicture.DEFAULT,
    ) {
        fun wants(k: Guard.Kind) = k in kinds
    }

    /** Учасники — рядок для пам'яті телефона: по рядку на людину, поля через табуляцію. */
    fun encode(members: List<Member>): String = members.joinToString("\n") { m ->
        listOf("${m.chatId}", m.name.replace('\t', ' ').replace('\n', ' '), m.kinds.joinToString(",") { it.key }, "${m.since}", m.look.key)
            .joinToString("\t")
    }

    fun decode(raw: String?): List<Member> = raw.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        val id = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val kinds = if (f.size > 2) f[2].split(',').mapNotNull { Guard.Kind.of(it) }.toSet() else Guard.Kind.ALL
        Member(id, f.getOrNull(1).orEmpty().ifBlank { "?" }, kinds, f.getOrNull(3)?.toLongOrNull() ?: 0L,
            GuardPicture.Look.of(f.getOrNull(4)) ?: GuardPicture.DEFAULT)
    }

    /** Новий код доступу з [random] (0 ≤ x < 1) — шість цифр, не з нуля, щоб не губився при копіюванні. */
    fun newCode(random: () -> Double): String = (100_000 + (random() * 900_000).toInt().coerceIn(0, 899_999)).toString()

    /**
     * T75 (faric 23.10 01:44): код доступу на екрані схований — лише крапки групами по три («••• •••»), без цифр з країв
     * (у коді 6 цифр — краї видали б 4 з 6). Торкнутись — показати / сховати, утримати — скопіювати повністю.
     * Пробіл нерозривний — крапки не ламаються на два рядки.
     */
    fun hiddenCode(code: String): String = "\u2022".repeat(code.length.coerceAtLeast(1)).chunked(3).joinToString("\u00A0")

    sealed class Action {
        data class Send(val chatId: Long, val text: String, val keyboard: List<List<Telegram.Button>>? = null) : Action()
        data class Edit(val chatId: Long, val messageId: Long, val text: String, val keyboard: List<List<Telegram.Button>>?) : Action()
        data class Answer(val callbackId: String, val text: String) : Action()
        /**
         * T59: стан батарей з картинкою у вигляді учасника — малює сторож (тут без Android). [grid] — одна спільна
         * картинка всіх батарей («Стан усіх батарей»); ні — картинка батареї, а коли їх кілька — теж сітка.
         */
        data class Status(val chatId: Long, val text: String, val look: GuardPicture.Look, val grid: Boolean,
            val keyboard: List<List<Telegram.Button>>? = null) : Action()

        /** T64: «/diag» — прочитати батарею й надіслати файл з даними для перевірки ([by] — хто попросив). */
        data class Diag(val chatId: Long, val by: String) : Action()
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
        /** «@AolithiumLive_bot» — щоб упізнати, що в групу додали саме цього бота (T58). */
        val botName: String = "",
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
            "menu" to tr("Кнопки: стан усіх батарей, вигляд", "Buttons: all batteries, picture look"),
            "diag" to tr("Файл з даними плати для перевірки", "File with BMS data for checking"),
            "stop" to tr("Більше не надсилати", "Stop sending"),
            "help" to tr("Як це працює", "How it works"),
        )

    fun handle(u: Telegram.Update, input: Input): Result {
        if (u.chatId == 0L) return Result(input.members, emptyList(), input.tries)
        val me = input.members.firstOrNull { it.chatId == u.chatId }
        u.callbackData?.let { return callback(u, it, me, input) }
        if (u.text == null && !u.private && me == null && (u.groupCreated ||
                (input.botName.isNotEmpty() && u.added.any { it.equals(input.botName, ignoreCase = true) }))) {
            // T58 (faric 21.10 17:51): бота додали в групу вручну — коду нема, і сторож мовчав. Підказати, як увімкнути.
            return Result(input.members, listOf(Action.Send(u.chatId, groupHint(input.botName, u.name))), input.tries,
                tr("мене додали в групу «${u.name}» без коду", "I was added to the group \"${u.name}\" without a code"))
        }
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
            cmd == "/status" || word == "стан" || word == "status" -> // uk-only: слово людини
                Result(input.members, listOf(Action.Status(u.chatId, input.status(), me.look, grid = false, keyboard = menuKeyboard())), input.tries)
            cmd == "/menu" || word == "меню" || word == "menu" -> // uk-only: слово людини
                Result(input.members, listOf(Action.Send(u.chatId, menuText(), menuKeyboard())), input.tries)
            cmd == "/settings" || cmd == "/start" || word == "налаштування" -> // uk-only: слово людини
                Result(input.members, listOf(Action.Send(u.chatId, settingsText(me), keyboard(me))), input.tries)
            cmd == "/diag" -> Result(input.members, listOf(Action.Diag(u.chatId, u.name)), input.tries,
                tr("${me.name} попросив дані для перевірки", "${me.name} asked for check data"))
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
        val welcome = if (u.private) tr(
            "Вітаю, ${u.name}! Тепер я надсилатиму тобі новини батареї. Нижче — що саме; торкнись, щоб вимкнути зайве.",
            "Welcome, ${u.name}! I will now send you battery news. Below is what exactly; tap to turn off what you don't need.",
        ) else tr(
            "Готово: тепер я писатиму в групу «${u.name}». Нижче — що саме; вибір спільний для всієї групи. " +
                "У групі команди — через кнопку «Меню» (там вони з моїм ім'ям, щоб відповів саме я).",
            "Done: I will now write to the group \"${u.name}\". Below is what exactly; the choice is shared by the whole group. " +
                "In a group, use the \"Menu\" button for commands (they carry my name there, so that I am the one who answers).",
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
        when (data) {
            "all" -> return Result(input.members, listOf(Action.Answer(id, ""),
                Action.Status(u.chatId, input.status(), me.look, grid = true, keyboard = menuKeyboard())), input.tries)
            "set" -> return Result(input.members, listOf(Action.Answer(id, ""),
                Action.Send(u.chatId, settingsText(me), keyboard(me))), input.tries)
        }
        if (data.startsWith("l:")) {
            val look = GuardPicture.Look.of(data.removePrefix("l:"))
                ?: return Result(input.members, listOf(Action.Answer(id, "")), input.tries)
            val updated = me.copy(look = look)
            val members = input.members.map { if (it.chatId == me.chatId) updated else it }
            val acts = mutableListOf<Action>(Action.Answer(id, tr("Картинка: ", "Picture: ") + look.title))
            if (msgId != null) acts += Action.Edit(u.chatId, msgId, settingsText(updated), keyboard(updated))
            return Result(members, acts, input.tries)
        }
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

    /** T58: що сказати в групу, куди бота додали без коду (сам код у групу не пишемо — його знає лише власник). */
    fun groupHint(botName: String, group: String): String {
        val cmd = "/start" + botName + " " + tr("КОД", "CODE")
        return tr(
            "Мене додали в групу «$group», але без коду доступу — тож поки я сюди не пишу. Щоб писав, власник сторожа " +
                "надсилає в цю групу:\n$cmd\n(КОД — 6 цифр із застосунку: батарея → «Ще» → «Сторож (Telegram)»). " +
                "Або там же — кнопка «Запросити в групу Telegram»: вона додає мене вже з кодом.",
            "I was added to the group \"$group\", but without the access code — so I do not write here yet. To make me write, " +
                "the guard's owner sends to this group:\n$cmd\n(CODE is 6 digits from the app: battery → \"More\" → " +
                "\"Guard (Telegram)\"). Or use the \"Invite to a Telegram group\" button there: it adds me with the code.",
        )
    }

    fun help(): String = tr(
        "Я стежу за батареєю й пишу, коли щось сталося. Команди:\n/status — що зараз з батареєю\n/menu — кнопки\n/settings — що тобі надсилати\n" +
            "/diag — файл з даними плати для перевірки\n/stop — більше не надсилати\nЩодня зранку пишу «Сторож працює». Не прийшло — зі сторожем щось сталося.",
        "I watch the battery and write when something happens. Commands:\n/status — what the battery is doing now\n/menu — buttons\n" +
            "/settings — what to send you\n/diag — file with BMS data for checking\n/stop — stop sending\nEvery morning I write \"The guard is working\". If it does not come, " +
            "something happened to the guard.",
    )

    fun settingsText(m: Member): String = tr(
        "Що надсилати тобі (${m.kinds.size} з ${Guard.Kind.entries.size}). Торкнись, щоб увімкнути або вимкнути. " +
            "Нижче — вигляд картинки батареї до кожного повідомлення (зараз: ${m.look.title.lowercase()}):",
        "What to send you (${m.kinds.size} of ${Guard.Kind.entries.size}). Tap to turn on or off. " +
            "Below is the look of the battery picture with each message (now: ${m.look.title.lowercase()}):",
    )

    /** T59: меню в чаті — кнопки під повідомленням (і в групі працюють без імені бота). */
    fun menuText(): String = tr("Що показати?", "What to show?")

    fun menuKeyboard(): List<List<Telegram.Button>> = listOf(
        listOf(Telegram.Button(tr("📊 Стан усіх батарей", "📊 All batteries"), "all")),
        listOf(Telegram.Button(tr("⚙️ Що надсилати й вигляд", "⚙️ What to send and look"), "set")),
    )

    fun summary(m: Member): String = if (m.kinds.isEmpty()) tr(
        "Усе вимкнено — я нічого не надсилатиму. Змінити — /settings.",
        "Everything is off — I will send nothing. To change — /settings.",
    ) else tr("Надсилатиму: ", "I will send: ") + Guard.Kind.entries.filter { m.wants(it) }.joinToString(", ") { it.title } +
        tr(". Картинка: ", ". Picture: ") + m.look.title.lowercase() + tr(". Змінити — /settings.", ". To change — /settings.")

    /** Кнопки: 🔔 — увімкнено, 🔕 — вимкнено (не галочки — правило інтерфейсу). */
    fun keyboard(m: Member): List<List<Telegram.Button>> =
        Guard.Kind.entries.map { k ->
            listOf(Telegram.Button((if (m.wants(k)) "🔔 " else "🔕 ") + k.title, "k:${k.key}"))
        } + GuardPicture.keyboard(m.look) + listOf(listOf(Telegram.Button(tr("Готово", "Done"), "done")))

    /** T52: група стала супергрупою — той самий учасник, новий номер чату. */
    fun migrate(members: List<Member>, from: Long, to: Long): List<Member> =
        if (members.any { it.chatId == to }) members.filterNot { it.chatId == from }
        else members.map { if (it.chatId == from) it.copy(chatId = to) else it }

    /** Учасник — група (номери груп у Telegram від'ємні). */
    fun isGroup(m: Member): Boolean = m.chatId < 0

    /** Кому слати це повідомлення. */
    fun recipients(members: List<Member>, kind: Guard.Kind): List<Long> = members.filter { it.wants(kind) }.map { it.chatId }
}

package ua.faric.aolithium.core

import java.util.Base64

/**
 * T54 «Передати сторожа на інший телефон» (faric 21.10 03:11: «якщо людина захоче, значить передасть»).
 *
 * Список людей і код доступу живуть у пам'яті телефона, а не в Telegram, — тож голий токен переносить лише бота, а люди
 * мусили б під'єднуватись заново. Текст передачі несе все разом: токен, ім'я бота, код, учасників, годину «я працюю».
 * Старий телефон після передачі вимикає сторожа сам (два телефони з одним ботом Telegram не пускає — помилка 409).
 *
 * Формат: `AOLGUARD1:` + Base64 (URL-safe) від JSON. Не шифр: текст — як ключ від бота, пересилати лише тій людині.
 */
object GuardTransfer {
    const val PREFIX = "AOLGUARD1:"

    data class Pack(
        val token: String,
        val botName: String,
        val code: String,
        val members: List<GuardBot.Member>,
        val dailyHour: Int,
    )

    fun encode(p: Pack): String {
        val json = JsonTree.write(linkedMapOf(
            "token" to p.token, "bot" to p.botName, "code" to p.code, "hour" to p.dailyHour,
            "members" to p.members.map { m ->
                linkedMapOf("id" to m.chatId, "name" to m.name, "kinds" to m.kinds.map { it.key }, "since" to m.since, "look" to m.look.key)
            },
        ))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** Знайти текст передачі будь-де у вставленому (месенджер міг додати слова, пробіли, переноси). Не він — null. */
    fun decode(raw: String): Pack? {
        val at = raw.indexOf(PREFIX)
        if (at < 0) return null
        val body = raw.substring(at + PREFIX.length).takeWhile { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it.isWhitespace() }
            .filterNot { it.isWhitespace() }
        val json = try {
            String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val o = JsonTree.obj(JsonTree.parseOrNull(json)) ?: return null
        val token = o["token"] as? String ?: return null
        if (!Telegram.tokenLooksRight(token)) return null
        val members = JsonTree.list(o["members"]).mapNotNull { x ->
            val m = JsonTree.obj(x) ?: return@mapNotNull null
            val id = JsonTree.long(m["id"]) ?: return@mapNotNull null
            val kinds = JsonTree.list(m["kinds"]).mapNotNull { k -> (k as? String)?.let { Guard.Kind.of(it) } }.toSet()
            GuardBot.Member(id, (m["name"] as? String).orEmpty().ifBlank { "?" }, kinds, JsonTree.long(m["since"]) ?: 0L,
                GuardPicture.Look.of(m["look"] as? String) ?: GuardPicture.DEFAULT)
        }.take(GuardBot.MAX_MEMBERS)
        val code = (o["code"] as? String).orEmpty().takeIf { it.length == GuardBot.CODE_LEN && it.all(Char::isDigit) } ?: ""
        val hour = JsonTree.long(o["hour"])?.toInt()?.takeIf { it in 0..23 } ?: Guard.DAILY_HOUR_DEFAULT
        return Pack(token, (o["bot"] as? String).orEmpty(), code, members, hour)
    }

    /**
     * T60 (faric 21.10 18:28: «щоб не загубились… сразу цей код вставити, і він далі продовжується»): копія сторожа —
     * той самий текст, що й передача, але сторож тут працює далі. Зберегти собі (Telegram «Збережене», нотатки, Диск);
     * знадобиться після встановлення застосунку заново: «Налаштувати сторожа» → вставити → «Перевірити й увімкнути».
     */
    fun backupMessage(p: Pack): String = tr(
        "Копія сторожа батареї Aolithium Live (${p.botName}, ${p.members.size} учасн.). Знадобиться, якщо застосунок " +
            "доведеться поставити заново: Aolithium Live → батарея → «Ще» → «Сторож (Telegram)» → «Налаштувати сторожа» → " +
            "встав увесь цей текст → «Перевірити й увімкнути» — бот, люди й код повернуться. Тримай лише в себе: це ключ від бота.\n\n",
        "Aolithium Live battery guard backup (${p.botName}, ${p.members.size} members). You need it if the app has to be " +
            "installed again: Aolithium Live → battery → \"More\" → \"Guard (Telegram)\" → \"Set up the guard\" → " +
            "paste all of this text → \"Check and turn on\" — the bot, members and code come back. Keep it to yourself: it is the key to the bot.\n\n",
    ) + encode(p)

    /** Що пересилає старий телефон: пояснення + сам текст (людина копіює все повідомлення — decode знайде своє). */
    fun message(p: Pack): String = tr(
        "Сторож батареї Aolithium Live (${p.botName}, ${p.members.size} учасн.). Відкрий Aolithium Live → батарея → «Ще» → " +
            "«Сторож (Telegram)» → «Налаштувати сторожа» → встав увесь цей текст → «Перевірити й увімкнути». " +
            "Нікому більше не пересилай: це ключ від бота.\n\n",
        "Aolithium Live battery guard (${p.botName}, ${p.members.size} members). Open Aolithium Live → battery → \"More\" → " +
            "\"Guard (Telegram)\" → \"Set up the guard\" → paste all of this text → \"Check and turn on\". " +
            "Do not forward to anyone else: this is the key to the bot.\n\n",
    ) + encode(p)
}

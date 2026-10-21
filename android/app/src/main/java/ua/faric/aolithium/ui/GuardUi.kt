package ua.faric.aolithium.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.Guard
import ua.faric.aolithium.core.GuardBot
import ua.faric.aolithium.core.GuardTransfer
import ua.faric.aolithium.core.Telegram
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.GuardSettings
import ua.faric.aolithium.guard.GuardRunner

/**
 * T48 «Сторож (Telegram)» — картка у вкладці «Ще» батареї і її вікна (налаштування, учасники, вимкнення).
 * faric 21.10 01:53: сторожем може бути будь-який телефон — ставиш застосунок і вмикаєш тут; людей може бути кілька,
 * кожен у чаті з ботом сам вибирає, що отримувати.
 *
 * [sample] — демо: показати, як виглядає налаштований сторож (знімки для інструкції), без мережі й без запису.
 */
class GuardUi(
    private val host: MainActivity,
    private val address: String,
    private val sample: Boolean,
    private val onChange: () -> Unit,
) {
    private val ui: Ui get() = host.ui
    private val st = GuardSettings(host)

    private val bot: String get() = if (sample) SAMPLE_BOT else st.botName
    private val code: String get() = if (sample) SAMPLE_CODE else st.code
    private val members: List<GuardBot.Member> get() = if (sample) SAMPLE_MEMBERS else st.members
    private val on: Boolean get() = sample || (st.enabled && st.token.isNotEmpty())

    fun card(): View {
        val card = if (on) ui.card(8, fill = Palette.accentFill, stroke = Palette.accent) else ui.card(8)
        card.addView(ui.label(tr("Сторож (Telegram)", "Guard (Telegram)"), LineIcon.SHIELD))
        if (!on) {
            card.addView(ui.muted(tr(
                "Телефон лишається вдома біля батареї й пише в Telegram тобі й рідним: світло зникло, мало заряду, захист плати, " +
                    "щоранку «я працюю». Потрібні інтернет і свій бот у Telegram (2 хв).",
                "The phone stays at home by the battery and writes to you and your family in Telegram: power outage, low charge, " +
                    "BMS protection, \"I am working\" every morning. Needs internet and your own Telegram bot (2 min).",
            ), 13f))
            if (st.token.isNotEmpty()) {
                card.addView(ui.button(tr("Увімкнути знову (${st.botName})", "Turn on again (${st.botName})"), Ui.Kind.PRIMARY) { turnOn() },
                    ui.matchWrap())
                card.addView(ui.button(tr("Інший бот", "Another bot"), Ui.Kind.NORMAL) { showSetup() }, ui.matchWrap())
            } else {
                card.addView(ui.button(tr("Налаштувати сторожа", "Set up the guard"), Ui.Kind.PRIMARY) { showSetup() }, ui.matchWrap())
            }
            card.addView(ui.iconButton(LineIcon.HELP, tr("Як це працює", "How it works"), Ui.Kind.QUIET) { host.openHelp("guard") },
                ui.matchWrap())
            return card
        }
        // T53 (скрін faric 21.10 03:04): ім'я бота — цілим (не влазить — під підписом), підписи — короткі, в один рядок.
        card.addView(ui.factOneLine(tr("Бот", "Bot"), bot.replace(" ", "\u00A0")))
        card.addView(ui.fact(tr("Код доступу", "Access code"), code))
        val groups = members.count { GuardBot.isGroup(it) }
        card.addView(ui.fact(tr("Учасники", "Members"), if (groups > 0) tr("${members.size} (груп: $groups)", "${members.size} ($groups groups)") else "${members.size}"))
        val watched = if (sample) 1 else BgSettings(host).enabled().count { !it.startsWith("demo:") }
        card.addView(ui.fact(tr("Батарей", "Batteries"), "$watched"))
        val sent = if (sample) tr("сьогодні о 09:00", "today at 09:00")
            else st.lastSentAt.takeIf { it > 0 }?.let { Background.hhmm(it) } ?: tr("ще нічого", "nothing yet")
        card.addView(ui.fact(tr("Надіслано", "Last sent"), sent))
        if (!sample && st.lastError.isNotEmpty()) {
            card.addView(ui.text(tr("Telegram: ", "Telegram: ") + st.lastError, 13f, Palette.warning))
        }
        if (!sample && !BgSettings(host).isOn(address)) {
            card.addView(ui.text(tr("Для цієї батареї вимкнено «Запис у фоні» — сторож її не бачить.",
                "\"Background recording\" is off for this battery — the guard cannot see it."), 13f, Palette.warning))
            card.addView(ui.button(tr("Стежити й за цією батареєю", "Watch this battery too"), Ui.Kind.PRIMARY) {
                watchThis()
                onChange()
            }, ui.matchWrap())
        }
        card.addView(ui.muted(tr(
            "Людина відкриває запрошення — Telegram сам надсилає боту код, і вона в списку. Далі кожен у чаті з ботом " +
                "вибирає, що йому слати (/settings).",
            "A person opens the invitation — Telegram sends the code to the bot, and they are on the list. Then everyone picks " +
                "in the bot chat what to get (/settings).",
        ), 12.5f))
        card.addView(ui.iconButton(LineIcon.LIST, tr("Запросити людину", "Invite a person"), Ui.Kind.PRIMARY) { invite() }, ui.matchWrap())
        // T52 (faric 21.10 03:11): за бажанням — у групу Telegram, куди можна звести кількох сторожів (свій, мами, брата).
        card.addView(ui.button(tr("Запросити в групу Telegram", "Invite to a Telegram group"), Ui.Kind.NORMAL) { inviteGroup() }, ui.matchWrap())
        card.addView(ui.grid(listOf(
            ui.button(tr("Учасники (${members.size})", "Members (${members.size})"), Ui.Kind.NORMAL) { showMembers() },
            ui.button(tr("Надіслати перевірку", "Send a test"), Ui.Kind.NORMAL) {
                if (!sample) GuardRunner.sendTest(host)
            },
        )), ui.matchWrap())
        card.addView(ui.text(tr("Щодня «я працюю» о:", "Daily \"I am working\" at:"), 14f, Palette.text, medium = true))
        val hour = if (sample) Guard.DAILY_HOUR_DEFAULT else st.dailyHour
        card.addView(ui.grid(HOURS.map { hh ->
            ui.choice("$hh:00", hh == hour) {
                if (!sample) {
                    st.dailyHour = hh
                    onChange()
                }
            }
        }, perRow = HOURS.size), ui.matchWrap())
        card.addView(ui.grid(listOf(
            ui.button(tr("Новий код", "New code"), Ui.Kind.NORMAL) {
                if (!sample) {
                    st.code = GuardBot.newCode { Math.random() }
                    onChange()
                }
            },
            ui.button(tr("Вимкнути сторожа", "Turn off the guard"), Ui.Kind.DANGER) { showOff() },
        )), ui.matchWrap())
        // T54 (faric 21.10 03:11): за бажанням — передати бота разом з людьми й кодом на інший телефон.
        card.addView(ui.button(tr("Передати сторожа на інший телефон", "Move the guard to another phone"), Ui.Kind.QUIET) { showMove() },
            ui.matchWrap())
        card.addView(ui.muted(tr(
            "Новий код — для нових людей; ті, хто вже в списку, лишаються. Повідомлення без інтернету чекають у черзі.",
            "A new code is for new people; those already on the list stay. Messages wait in a queue while there is no internet.",
        ), 12.5f))
        return card
    }

    // ---------- дії ----------

    private fun watchThis() {
        val bg = BgSettings(host)
        bg.set(address, true)
        val batteries = Batteries(host)
        if (batteries.find(address) == null) batteries.remember(address, Batteries.scanNames[address], Batteries.kindOf(host, address))
        RecordService.ensure(host)
    }

    private fun turnOn() {
        if (sample) return
        if (st.code.length != GuardBot.CODE_LEN) st.code = GuardBot.newCode { Math.random() }
        st.dailyDay = Guard.dayKey(System.currentTimeMillis()) // перше «я працюю» — завтра; зараз є «Надіслати перевірку»
        st.enabled = true
        watchThis()
        host.requestNotifications()
        GuardRunner.ensure(host)
        onChange()
    }

    private fun invite() {
        val name = bot.removePrefix("@")
        val text = tr(
            "Під'єднайся до сторожа нашої батареї в Telegram — писатиме, коли зникне світло, сяде батарея чи щось трапиться:\n" +
                "https://t.me/$name?start=$code\n\nЯкщо посилання не відкривається: знайди в Telegram $bot і надішли йому код $code",
            "Join our battery guard in Telegram — it writes when the power goes out, the battery runs low, or something happens:\n" +
                "https://t.me/$name?start=$code\n\nIf the link does not open: find $bot in Telegram and send it the code $code",
        )
        host.shareText(tr("Сторож батареї", "Battery guard"), text)
    }

    private fun inviteGroup() {
        val name = bot.removePrefix("@")
        val text = tr(
            "Додай сторожа батареї в групу Telegram — він писатиме туди: відкрий посилання, вибери групу (треба бути її адміном):\n" +
                "https://t.me/$name?startgroup=$code\n\nТак можна звести в одну групу кількох сторожів (свій, мами, брата) — " +
                "кожне повідомлення підписане назвою батареї.",
            "Add the battery guard to a Telegram group — it will write there: open the link, pick the group (you must be its admin):\n" +
                "https://t.me/$name?startgroup=$code\n\nThis way several guards (yours, mom's, brother's) can share one group — " +
                "each message is signed with the battery name.",
        )
        host.shareText(tr("Сторож батареї в групу", "Battery guard to a group"), text)
    }

    private fun showMove() {
        val box = host.overlay.dialog(
            tr("Передати сторожа?", "Move the guard?"),
            tr(
                "Бот, учасники (${members.size}) і код перейдуть на інший телефон — людям не треба під'єднуватись заново. " +
                    "Тут сторож вимкнеться одразу (два телефони з одним ботом Telegram не пускає). Далі вибери, кому надіслати " +
                    "текст передачі, — лише тій людині: це ключ від бота. На тому телефоні: «Налаштувати сторожа» → вставити текст.",
                "The bot, members (${members.size}) and code move to another phone — people do not need to join again. " +
                    "The guard turns off here right away (Telegram does not allow two phones with one bot). Then pick whom to send " +
                    "the transfer text to — only that person: it is the key to the bot. On that phone: \"Set up the guard\" → paste the text.",
            ),
            actions = listOf(tr("Передати", "Move") to Ui.Kind.PRIMARY, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0 && !sample) {
                    val pack = GuardTransfer.Pack(st.token, st.botName, st.code, st.members, st.dailyHour)
                    st.turnOff()
                    GuardRunner.stop()
                    onChange()
                    host.shareText(tr("Сторож батареї — передача", "Battery guard — transfer"), GuardTransfer.message(pack))
                }
            },
        )
        host.overlay.show(KEY_MOVE, box)
    }

    fun showSetup() {
        val field = EditText(host).apply {
            setText(draft)
            hint = "123456789:AA…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(Palette.text)
            setHintTextColor(Palette.faint)
            textSize = 15f
            background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minimumHeight = ui.dp(Ui.MIN_TOUCH_DP)
            maxLines = 4 // T54: текст передачі довгий — видно початок, не розтягує вікно
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    draft = s?.toString().orEmpty()
                }
            })
        }
        val status = ui.muted("", 13f)
        val extra = ui.column(10).apply {
            addView(ui.text(tr(
                "1. У Telegram знайди @BotFather → «Start» → надішли /newbot.\n" +
                    "2. Назва — будь-яка («Батарея вдома»); ім'я — латиницею й закінчується на bot (aolithium_home_bot).\n" +
                    "3. BotFather надішле токен — довгий рядок «123456789:AA…». Скопіюй і встав нижче.\n" +
                    "Переносиш сторожа з іншого телефона — встав увесь текст передачі.",
                "1. In Telegram find @BotFather → \"Start\" → send /newbot.\n" +
                    "2. Name — any (\"Home battery\"); username — Latin letters, ending in bot (aolithium_home_bot).\n" +
                    "3. BotFather sends a token — a long line \"123456789:AA…\". Copy it and paste below.\n" +
                    "Moving the guard from another phone — paste the whole transfer text.",
            ), 14f, Palette.text))
            addView(field, ui.matchWrap())
            addView(status, ui.matchWrap())
            addView(ui.muted(tr(
                "Токен — як пароль бота: лишається лише на цьому телефоні. Застосунок шле лише в Telegram і лише людям, які під'єдналися твоїм кодом.",
                "The token is like the bot's password: it stays on this phone only. The app sends only to Telegram and only to people who joined with your code.",
            ), 12.5f))
        }
        val box = host.overlay.dialog(
            tr("Сторож: свій бот у Telegram", "Guard: your own Telegram bot"), "",
            actions = listOf(tr("Перевірити й увімкнути", "Check and turn on") to Ui.Kind.PRIMARY, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 1) {
                    host.overlay.dismiss()
                    return@dialog
                }
                if (sample) return@dialog
                val pack = GuardTransfer.decode(field.text.toString())
                val token = pack?.token ?: Telegram.cleanToken(field.text.toString())
                if (!Telegram.tokenLooksRight(token)) {
                    status.setTextColor(Palette.warning)
                    status.text = tr("Це не схоже на токен: має бути «цифри:літери», 40+ знаків. Скопіюй повністю з повідомлення BotFather.",
                        "This does not look like a token: it should be \"digits:letters\", 40+ characters. Copy it in full from BotFather's message.")
                    return@dialog
                }
                status.setTextColor(Palette.muted)
                status.text = tr("Перевіряю в Telegram…", "Checking with Telegram…")
                GuardRunner.checkToken(token) { name, error ->
                    if (name == null) {
                        status.setTextColor(Palette.warning)
                        status.text = error ?: ""
                        return@checkToken
                    }
                    if (pack != null) {
                        // T54: передача — бот разом з людьми, кодом і годиною «я працюю».
                        st.offset = 0L
                        st.members = pack.members
                        st.code = pack.code.ifEmpty { GuardBot.newCode { Math.random() } }
                        st.dailyHour = pack.dailyHour
                    } else if (token != st.token) {
                        st.offset = 0L
                        st.members = emptyList() // новий бот — люди під'єднуються до нього заново
                        st.code = GuardBot.newCode { Math.random() }
                    }
                    st.token = token
                    st.botName = name
                    st.lastError = ""
                    draft = ""
                    GuardRunner.stop()
                    turnOn()
                    host.overlay.dismiss()
                }
            },
            extra = extra,
        )
        host.overlay.show(KEY_SETUP, box, dismissOnOutside = false)
    }

    fun showMembers() {
        val list = ui.column(10)
        if (members.isEmpty()) {
            list.addView(ui.muted(tr("Поки нікого. Натисни «Запросити людину» й перешли запрошення — собі теж.",
                "Nobody yet. Tap \"Invite a person\" and forward the invitation — to yourself too."), 14f))
        }
        for (m in members) {
            val row = ui.row(10)
            val info = ui.column(2)
            info.addView(ui.text(m.name, 15f, Palette.text, medium = true))
            val kind = if (GuardBot.isGroup(m)) tr("група · ", "group · ") else ""
            info.addView(ui.muted(kind + tr("отримує ${m.kinds.size} з ${Guard.Kind.entries.size}", "gets ${m.kinds.size} of ${Guard.Kind.entries.size}"), 12.5f))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.button(tr("Прибрати", "Remove"), Ui.Kind.DANGER) {
                if (!sample) {
                    st.members = st.members.filterNot { it.chatId == m.chatId }
                    onChange()
                    showMembers()
                }
            })
            list.addView(row, ui.matchWrap())
        }
        val box = host.overlay.dialog(
            tr("Учасники сторожа", "Guard members"),
            tr("Хто отримує повідомлення. Що саме — кожен вибирає сам у чаті з ботом (/settings).",
                "Who gets messages. What exactly — everyone picks in the bot chat (/settings)."),
            actions = listOf(tr("Закрити", "Close") to Ui.Kind.QUIET),
            onAction = { host.overlay.dismiss() },
            extra = list,
        )
        host.overlay.show(KEY_MEMBERS, box)
    }

    private fun showOff() {
        val box = host.overlay.dialog(
            tr("Вимкнути сторожа?", "Turn off the guard?"),
            tr("Повідомлення в Telegram перестануть іти. Бот, учасники й код лишаються — увімкнути знову можна однією кнопкою.",
                "Telegram messages will stop. The bot, members and code stay — you can turn it on again with one button."),
            actions = listOf(tr("Вимкнути", "Turn off") to Ui.Kind.DANGER, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0 && !sample) {
                    st.turnOff()
                    GuardRunner.stop()
                    onChange()
                }
            },
        )
        host.overlay.show(KEY_OFF, box)
    }

    fun restore(key: String): Boolean {
        when (key) {
            KEY_SETUP -> showSetup()
            KEY_MEMBERS -> showMembers()
            KEY_OFF -> showOff()
            KEY_MOVE -> showMove()
            else -> return false
        }
        return true
    }

    companion object {
        const val KEY_SETUP = "guard:setup"
        const val KEY_MEMBERS = "guard:members"
        const val KEY_OFF = "guard:off"
        const val KEY_MOVE = "guard:move"
        val HOURS = listOf(7, 9, 12, 20)

        /** Чернетка токена — переживає перестворення екрана (зміна мови), але не перезапуск застосунку. */
        private var draft: String = ""

        // Демо (знімки інструкції): вигадані бот, код і люди.
        private const val SAMPLE_BOT = "@aolithium_home_bot"
        private const val SAMPLE_CODE = "482913"
        private val SAMPLE_MEMBERS: List<GuardBot.Member>
            get() = listOf(
                GuardBot.Member(1, tr("Олег", "Oleh")),
                GuardBot.Member(2, tr("Саня", "Sanya"), Guard.Kind.ALL - Guard.Kind.STEP),
                GuardBot.Member(3, tr("Мама", "Mom"), setOf(Guard.Kind.OUTAGE, Guard.Kind.LOW, Guard.Kind.DAILY)),
            )
    }
}

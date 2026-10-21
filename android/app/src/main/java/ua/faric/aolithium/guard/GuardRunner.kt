package ua.faric.aolithium.guard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Guard
import ua.faric.aolithium.core.GuardBot
import ua.faric.aolithium.core.Telegram
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.GuardSettings
import ua.faric.aolithium.data.LastSnapshot

/**
 * T48 «Сторож (Telegram)» — Android-частина. Окремий потік:
 *  - **довге опитування** бота (`getUpdates`, до 25 с): люди під'єднуються кодом, вибирають, що їм слати, питають /status;
 *  - **черга повідомлень**: що каже `core/Guard` про кожен знімок батареї — кожному учаснику, хто цей вид не вимкнув;
 *    нема інтернету (світло зникло, роутер вимкнувся) — лежить у черзі й піде, щойно зв'язок повернеться;
 *  - **щоденне «я працюю»** у вибрану годину.
 *
 * Знімки приходять звідти ж, звідки й сповіщення в шторці (`Notifier`): фон раз на ~5 хв і відкритий екран.
 * Демо-батареї сторож не чіпає. Батареї сторожа — ті, для яких увімкнено «Запис у фоні».
 */
object GuardRunner {
    private const val POLL_S = 25
    private const val RETRY_MS = 30_000L
    private const val TICK_MS = 60_000L

    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var polling = false
    private var tries: Map<Long, GuardBot.Tries> = emptyMap()
    private var commandsSet = false
    private var wake: PowerManager.WakeLock? = null

    /** Для екрана: чи живе потік сторожа зараз. */
    val running: Boolean get() = h != null

    /** Запустити, якщо сторожа увімкнено (повторний виклик нічого не ламає). Будь-який потік. */
    @Synchronized
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        val st = GuardSettings(app)
        if (!st.enabled || st.token.isEmpty()) {
            stop()
            return
        }
        if (h != null) return
        val t = HandlerThread("guard").apply { start() }
        thread = t
        h = Handler(t.looper)
        polling = false
        h?.post { loop(app) }
        h?.post(object : Runnable {
            override fun run() {
                tick(app, System.currentTimeMillis())
                h?.postDelayed(this, TICK_MS)
            }
        })
        LinkLog.add(tr("сторож: запущено", "guard: started"))
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        h?.removeCallbacksAndMessages(null)
        h = null
        thread = null
        t.quitSafely()
        LinkLog.add(tr("сторож: зупинено", "guard: stopped"))
    }

    // ---------- знімки (кличе Notifier) ----------

    fun onSnapshot(ctx: Context, address: String, s: BatterySnapshot, atMs: Long) {
        if (address.startsWith("demo:")) return
        val app = ctx.applicationContext
        if (!GuardSettings(app).enabled || !BgSettings(app).isOn(address)) return
        ensure(app)
        h?.post {
            val st = GuardSettings(app)
            val step = Guard.onSnapshot(st.state(address), s, atMs, name(app, address))
            st.save(address, step.state)
            queue(app, step.messages)
        }
    }

    fun onNoData(ctx: Context, address: String, now: Long) {
        if (address.startsWith("demo:")) return
        val app = ctx.applicationContext
        if (!GuardSettings(app).enabled || !BgSettings(app).isOn(address)) return
        ensure(app)
        h?.post {
            val st = GuardSettings(app)
            val lastOk = listOfNotNull(BgSettings(app).result(address)?.takeIf { it.ok }?.at, LastSnapshot(app).load(address)?.second).maxOrNull()
            val step = Guard.onNoData(st.state(address), lastOk, now, name(app, address))
            if (step.messages.isNotEmpty()) {
                st.save(address, step.state)
                queue(app, step.messages)
            }
        }
    }

    /** «Надіслати перевірку» на телефоні-сторожі: усім учасникам, незалежно від їхніх перемикачів. */
    fun sendTest(ctx: Context) {
        val app = ctx.applicationContext
        ensure(app)
        h?.post {
            val st = GuardSettings(app)
            val text = "🧪 " + tr("Перевірка сторожа", "Guard test") + "\n" + Guard.status(seen(app), System.currentTimeMillis())
            st.outbox = st.outbox + st.members.map { it.chatId to text }
            flush(app)
        }
    }

    /** Перевірити токен (`getMe`) — у фоновому потоці; [done] — на головному: ім'я бота або помилка. */
    fun checkToken(token: String, done: (bot: String?, error: String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = TelegramHttp.call(token, "getMe", "{}")
            val bot = if (r.ok) Telegram.botName(r) else null
            main.post { if (bot != null) done(bot, null) else done(null, Telegram.explain(r)) }
        }, "guard-check").start()
    }

    // ---------- внутрішнє (потік сторожа) ----------

    private fun name(ctx: Context, address: String): String =
        Batteries(ctx).find(address)?.title ?: BmsMatch.shortAddress(address)

    private fun seen(ctx: Context): List<Guard.Seen> = BgSettings(ctx).enabled().filterNot { it.startsWith("demo:") }.sorted().map { a ->
        val last = LastSnapshot(ctx).load(a)
        Guard.Seen(name(ctx, a), last?.first, last?.second)
    }

    private fun queue(ctx: Context, msgs: List<Guard.Msg>) {
        if (msgs.isEmpty()) return
        val st = GuardSettings(ctx)
        val members = st.members
        val add = ArrayList<Pair<Long, String>>()
        for (m in msgs) {
            LinkLog.add(tr("сторож: ${m.title}", "guard: ${m.title}"))
            for (id in GuardBot.recipients(members, m.kind)) add += id to m.render()
        }
        if (add.isEmpty()) return
        st.outbox = st.outbox + add
        flush(ctx)
    }

    /** Надіслати все з черги по черзі. Нема зв'язку — лишити, спробуємо на наступному колі опитування. */
    private fun flush(ctx: Context) {
        val st = GuardSettings(ctx)
        var box = st.outbox
        if (box.isEmpty()) return
        holdAwake(ctx)
        val token = st.token
        while (box.isNotEmpty()) {
            val (chat, text) = box.first()
            val r = TelegramHttp.call(token, "sendMessage", Telegram.sendBody(chat, text))
            when {
                r.ok -> {
                    st.lastSentAt = System.currentTimeMillis()
                    st.lastError = ""
                }
                r.migrateTo != null -> {
                    // T52: група стала супергрупою — новий номер; переписати учасника й чергу і спробувати знову.
                    val to = r.migrateTo
                    st.members = GuardBot.migrate(st.members, chat, to)
                    box = box.map { if (it.first == chat) to to it.second else it }
                    st.outbox = box
                    LinkLog.add(tr("сторож: група стала супергрупою — оновлено", "guard: the group became a supergroup — updated"))
                    continue
                }
                Telegram.goneForGood(r) -> {
                    val gone = st.members.firstOrNull { it.chatId == chat }
                    st.members = st.members.filterNot { it.chatId == chat }
                    box = box.filterNot { it.first == chat }
                    st.outbox = box
                    LinkLog.add(tr("сторож: ${gone?.name ?: chat} заблокував бота — прибрано", "guard: ${gone?.name ?: chat} blocked the bot — removed"))
                    continue
                }
                r.code == 429 || r.code == 0 || r.code >= 500 -> {
                    st.lastError = Telegram.explain(r)
                    return // спробуємо пізніше, черга лишається
                }
                else -> st.lastError = Telegram.explain(r) // зіпсоване повідомлення — не застрягати на ньому
            }
            box = box.drop(1)
            st.outbox = box
        }
    }

    private fun loop(ctx: Context) {
        val handler = h ?: return
        if (polling) return
        polling = true
        val st = GuardSettings(ctx)
        if (!st.enabled || st.token.isEmpty()) {
            polling = false
            return
        }
        if (!commandsSet) {
            commandsSet = TelegramHttp.call(st.token, "setMyCommands", Telegram.commandsBody(GuardBot.commands)).ok
        }
        flush(ctx)
        val r = TelegramHttp.call(st.token, "getUpdates", Telegram.updatesBody(st.offset, POLL_S), readTimeoutMs = (POLL_S + 15) * 1000)
        var next = 0L
        if (r.ok) {
            for (u in Telegram.updates(r)) {
                st.offset = u.updateId + 1
                answer(ctx, u)
            }
            if (st.lastError.isNotEmpty() && st.outbox.isEmpty()) st.lastError = ""
        } else {
            st.lastError = Telegram.explain(r)
            next = RETRY_MS
            if (r.code == 401 || r.code == 404) {
                LinkLog.add(tr("сторож: Telegram не прийняв токен — зупиняюсь", "guard: Telegram rejected the token — stopping"))
                polling = false
                return
            }
        }
        polling = false
        handler.postDelayed({ loop(ctx) }, next)
    }

    private fun answer(ctx: Context, u: Telegram.Update) {
        val st = GuardSettings(ctx)
        val res = GuardBot.handle(u, GuardBot.Input(st.code, st.members, { Guard.status(seen(ctx), System.currentTimeMillis()) },
            System.currentTimeMillis(), tries))
        tries = res.tries
        if (res.members != st.members) st.members = res.members
        res.note?.let { LinkLog.add(tr("сторож: ", "guard: ") + it) }
        for (a in res.actions) {
            when (a) {
                is GuardBot.Action.Send -> TelegramHttp.call(st.token, "sendMessage", Telegram.sendBody(a.chatId, a.text, a.keyboard))
                is GuardBot.Action.Edit -> TelegramHttp.call(st.token, "editMessageText", Telegram.editBody(a.chatId, a.messageId, a.text, a.keyboard))
                is GuardBot.Action.Answer -> TelegramHttp.call(st.token, "answerCallbackQuery", Telegram.answerBody(a.callbackId, a.text))
            }
        }
    }

    /** Раз на хвилину: «я працюю», якщо пора. («Не бачу батарею» кличе фон через [onNoData].) */
    private fun tick(ctx: Context, now: Long) {
        val st = GuardSettings(ctx)
        if (!st.enabled) return
        if (Guard.dailyDue(st.dailyDay, now, st.dailyHour)) {
            st.dailyDay = Guard.dayKey(now)
            queue(ctx, listOf(Guard.daily(seen(ctx), now)))
        } else if (st.outbox.isNotEmpty()) {
            flush(ctx)
        }
    }

    private fun holdAwake(ctx: Context) {
        val l = wake ?: (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aolithium:guard")
            ?.apply { setReferenceCounted(false) }
            ?.also { wake = it }
            ?: return
        l.acquire(60_000L)
    }
}

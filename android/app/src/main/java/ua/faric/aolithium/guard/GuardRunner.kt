package ua.faric.aolithium.guard

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.ble.DiagFrames
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.BatterySnapshot
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Diag
import ua.faric.aolithium.core.Background
import ua.faric.aolithium.core.Guard
import ua.faric.aolithium.core.Outages
import ua.faric.aolithium.core.GuardBot
import ua.faric.aolithium.core.GuardPicture
import ua.faric.aolithium.core.Telegram
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.AlertSettings
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.GuardSettings
import ua.faric.aolithium.data.LastSnapshot

/**
 * T48 «Сторож (Telegram)» — Android-частина. Окремий потік:
 *  - **довге опитування** бота (`getUpdates`, до 25 с): люди під'єднуються кодом, вибирають, що їм слати, питають /status;
 *  - **черга повідомлень**: що каже `core/Guard` про кожен знімок батареї — кожному учаснику, хто цей вид не вимкнув;
 *    нема інтернету (світло зникло, роутер вимкнувся) — лежить у черзі й піде, щойно зв'язок повернеться;
 *  - **щоденне «я працюю»** у вибрану годину;
 *  - T59: до кожного повідомлення — **картинка батареї** у вигляді, який учасник вибрав (`guard/GuardArt.kt`).
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
    private var picSeq = 0

    /** Про що картинка: одна батарея (знімок, з якого повідомлення) або стан усіх батарей сторожа. */
    private sealed class Pic {
        data class One(val name: String, val snapshot: BatterySnapshot?, val at: Long?, val outageA: Double = Outages.START_A) : Pic()
        object All : Pic()
    }

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
            val nm = name(app, address)
            val oa = AlertSettings(app).outageA(address) // T85
            val step = Guard.onSnapshot(st.state(address), s, atMs, nm, outageA = oa)
            st.save(address, step.state)
            queue(app, step.messages, Pic.One(nm, s, atMs, oa))
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
            val nm = name(app, address)
            // T81: у повідомленні — конкретна причина останньої невдачі (не видно в ефірі / слабкий сигнал / …).
            val reason = BgSettings(app).result(address)?.takeIf { !it.ok && it.note.isNotBlank() }?.note
                ?.let { Background.Skip.localize(it) }
            val step = Guard.onNoData(st.state(address), lastOk, now, nm, reason = reason)
            if (step.messages.isNotEmpty()) {
                st.save(address, step.state)
                val last = LastSnapshot(app).load(address)
                queue(app, step.messages, Pic.One(nm, last?.first, last?.second, AlertSettings(app).outageA(address)))
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
            st.outbox = st.outbox + outItems(app, st.members, text, Pic.All)
            flush(app)
        }
    }

    /**
     * T50: «Перевірка тривоги» — усім учасникам зразок найгіршої тривоги з номерами, позначений «ПЕРЕВІРКА»
     * (незалежно від їхніх перемикачів, як «Надіслати перевірку»).
     */
    fun sendUrgentTest(ctx: Context) {
        val app = ctx.applicationContext
        if (!GuardSettings(app).enabled) return
        ensure(app)
        h?.post {
            val st = GuardSettings(app)
            val names = seen(app).joinToString(", ") { it.name }
            val text = "🧪 " + Guard.urgentTest(names).render(AlertSettings(app).callPlan)
            st.outbox = st.outbox + outItems(app, st.members, text, Pic.All)
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
        Guard.Seen(name(ctx, a), last?.first, last?.second, AlertSettings(ctx).outageA(a))
    }

    private fun queue(ctx: Context, msgs: List<Guard.Msg>, pic: Pic) {
        if (msgs.isEmpty()) return
        val st = GuardSettings(ctx)
        val members = st.members
        val call = AlertSettings(ctx).callPlan // T50: номери під серйозною тривогою
        val add = ArrayList<GuardPicture.Out>()
        val pics = HashMap<GuardPicture.Look, String?>() // одна картинка кожного вигляду на всю пачку
        for (m in msgs) {
            LinkLog.add(tr("сторож: ${m.title}", "guard: ${m.title}"))
            for (mem in members.filter { it.wants(m.kind) }) {
                val photo = pics.getOrPut(mem.look) { savePic(ctx, mem.look, pic) }
                add += GuardPicture.Out(mem.chatId, m.render(call), photo)
            }
        }
        if (add.isEmpty()) return
        st.outbox = st.outbox + add
        flush(ctx)
    }

    /** Той самий текст усім [members] — кожному з картинкою його вигляду. */
    private fun outItems(ctx: Context, members: List<GuardBot.Member>, text: String, pic: Pic): List<GuardPicture.Out> {
        val pics = HashMap<GuardPicture.Look, String?>()
        return members.map { m -> GuardPicture.Out(m.chatId, text, pics.getOrPut(m.look) { savePic(ctx, m.look, pic) }) }
    }

    /** Намалювати картинку й покласти у файл до черги; не вийшло — null (піде лише текст). */
    private fun savePic(ctx: Context, look: GuardPicture.Look, pic: Pic): String? {
        val bmp = render(ctx, look, pic, grid = false) ?: return null
        val st = GuardSettings(ctx)
        val name = GuardPicture.fileName(System.currentTimeMillis(), picSeq++, look)
        return try {
            st.picDir.mkdirs()
            java.io.File(st.picDir, name).writeBytes(GuardArt.png(bmp))
            name
        } catch (e: Exception) {
            null
        }
    }

    private fun render(ctx: Context, look: GuardPicture.Look, pic: Pic, grid: Boolean): Bitmap? {
        if (look == GuardPicture.Look.NONE) return null
        val now = System.currentTimeMillis()
        return when (pic) {
            is Pic.One -> GuardArt.battery(ctx, look, pic.name, pic.snapshot, pic.at, now, pic.outageA)
            Pic.All -> {
                val all = seen(ctx)
                when {
                    all.isEmpty() -> null
                    all.size == 1 && !grid -> GuardArt.battery(ctx, look, all[0].name, all[0].snapshot, all[0].at, now, all[0].outageA)
                    else -> GuardArt.grid(ctx, all, now)
                }
            }
        }
    }

    /** Надіслати все з черги по черзі. Нема зв'язку — лишити, спробуємо на наступному колі опитування. */
    private fun flush(ctx: Context) {
        val st = GuardSettings(ctx)
        var box = st.outbox
        if (box.isEmpty()) return
        holdAwake(ctx)
        val token = st.token
        while (box.isNotEmpty()) {
            val item = box.first()
            val chat = item.chatId
            val png = item.photo?.let { f -> java.io.File(st.picDir, f).takeIf { it.isFile }?.let { readOrNull(it) } }
            val split = GuardPicture.split(item.text)
            var r = if (png != null) TelegramHttp.sendPhoto(token, chat, png, split.caption)
                else TelegramHttp.call(token, "sendMessage", Telegram.sendBody(chat, item.text))
            if (png != null && !r.ok && r.code == 400 && !Telegram.goneForGood(r) && r.migrateTo == null) {
                // Картинку не прийняли (зіпсований файл тощо) — не губити повідомлення: надіслати текстом.
                r = TelegramHttp.call(token, "sendMessage", Telegram.sendBody(chat, item.text))
            } else if (png != null && r.ok && split.followUp != null) {
                // Картинка пішла з коротким підписом; повний текст — наступним повідомленням (і він переживе обрив).
                box = listOf(GuardPicture.Out(chat, split.followUp)) + box.drop(1)
                st.outbox = box
                st.lastSentAt = System.currentTimeMillis()
                continue
            }
            when {
                r.ok -> {
                    st.lastSentAt = System.currentTimeMillis()
                    st.lastError = ""
                }
                r.migrateTo != null -> {
                    // T52: група стала супергрупою — новий номер; переписати учасника й чергу і спробувати знову.
                    val to = r.migrateTo
                    st.members = GuardBot.migrate(st.members, chat, to)
                    box = box.map { if (it.chatId == chat) it.copy(chatId = to) else it }
                    st.outbox = box
                    LinkLog.add(tr("сторож: група стала супергрупою — оновлено", "guard: the group became a supergroup — updated"))
                    continue
                }
                Telegram.goneForGood(r) -> {
                    val gone = st.members.firstOrNull { it.chatId == chat }
                    st.members = st.members.filterNot { it.chatId == chat }
                    box = box.filterNot { it.chatId == chat }
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
        st.cleanPics()
    }

    private fun readOrNull(f: java.io.File): ByteArray? = try {
        f.readBytes()
    } catch (e: Exception) {
        null
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
            System.currentTimeMillis(), tries, st.botName))
        tries = res.tries
        if (res.members != st.members) st.members = res.members
        res.note?.let { LinkLog.add(tr("сторож: ", "guard: ") + it) }
        for (a in res.actions) {
            when (a) {
                is GuardBot.Action.Send -> TelegramHttp.call(st.token, "sendMessage", Telegram.sendBody(a.chatId, a.text, a.keyboard))
                is GuardBot.Action.Edit -> TelegramHttp.call(st.token, "editMessageText", Telegram.editBody(a.chatId, a.messageId, a.text, a.keyboard))
                is GuardBot.Action.Answer -> TelegramHttp.call(st.token, "answerCallbackQuery", Telegram.answerBody(a.callbackId, a.text))
                is GuardBot.Action.Status -> status(ctx, st.token, a)
                is GuardBot.Action.Diag -> diag(ctx, st.token, a)
            }
        }
    }

    /** T59: «/status», «Стан усіх батарей» — одразу, з картинкою вигляду учасника; не вийшло з картинкою — текстом. */
    private fun status(ctx: Context, token: String, a: GuardBot.Action.Status) {
        val bmp = if (a.look == GuardPicture.Look.NONE) null else render(ctx, if (a.grid) GuardPicture.Look.WIDGET else a.look, Pic.All, a.grid)
        if (bmp != null) {
            val split = GuardPicture.split(a.text)
            val r = TelegramHttp.sendPhoto(token, a.chatId, GuardArt.png(bmp), split.caption, if (split.followUp == null) a.keyboard else null)
            if (r.ok) {
                split.followUp?.let { TelegramHttp.call(token, "sendMessage", Telegram.sendBody(a.chatId, it, a.keyboard)) }
                return
            }
        }
        TelegramHttp.call(token, "sendMessage", Telegram.sendBody(a.chatId, a.text, a.keyboard))
    }

    private var lastDiagAt = 0L

    /**
     * T64: «/diag» — попросити фон свіже читання, почекати до [Diag.WAIT_MS] і надіслати файл з даними для перевірки
     * туди, звідки попросили. Не частіше, ніж раз на [Diag.COOLDOWN_MS].
     */
    private fun diag(ctx: Context, token: String, a: GuardBot.Action.Diag) {
        val now = System.currentTimeMillis()
        if (now - lastDiagAt < Diag.COOLDOWN_MS) {
            TelegramHttp.call(token, "sendMessage", Telegram.sendBody(a.chatId, tr(
                "Щойно збирав — спробуй за пару хвилин.", "I have just collected it — try again in a couple of minutes.")))
            return
        }
        lastDiagAt = now
        val before = DiagFrames.lastAt()
        val doneBefore = RecordService.finishedReads // T73
        val reading = RecordService.requestRead()
        TelegramHttp.call(token, "sendMessage", Telegram.sendBody(a.chatId, if (reading) tr(
            "Читаю батарею й збираю дані для перевірки — до хвилини.", "Reading the battery and collecting check data — up to a minute.")
            else tr("«Запис у фоні» не працює — надішлю те, що є з останнього читання.",
                "\"Background recording\" is not running — I will send what I have from the last read.")))
        val started = System.currentTimeMillis()
        fun finished() = RecordService.finishedReads > doneBefore
        fun send() {
            val fresh = if (reading) Diag.freshness(DiagFrames.lastAt() > before, finished()) else Diag.Fresh.LAST
            val text = DiagReport.build(ctx, a.by, fresh)
            val r = TelegramHttp.sendDocument(token, a.chatId, Diag.fileName(System.currentTimeMillis()), text, tr(
                "📎 Дані для перевірки. Перешли цей файл Claude.", "📎 Check data. Forward this file to Claude."))
            if (!r.ok) {
                TelegramHttp.call(token, "sendMessage", Telegram.sendBody(a.chatId, tr("Не вдалося надіслати файл: ", "Could not send the file: ") +
                    Telegram.explain(r)))
            }
            LinkLog.add(tr("сторож: дані для перевірки — ", "guard: check data — ") + if (r.ok) "OK" else Telegram.explain(r))
        }
        // T66: надіслати, щойно фон дочитав (а не через усі 45 с); фон не працює — одразу з останнього читання.
        // T68 (файл 22.10 03:20 — 25 с): чекати й слати в **окремому потоці** — потік сторожа більшу частину часу стоїть
        // у довгому опитуванні Telegram (до 25 с), і перевірка в його черзі чекала б, поки опитування повернеться.
        Thread({
            try {
                if (reading) {
                    // T73: фон закінчив і невдало (батарея далеко) — теж одразу, а не через 45 с.
                    while (!Diag.readyToSend(System.currentTimeMillis() - started, DiagFrames.lastAt() > before,
                            RecordService.reading, finished())) {
                        Thread.sleep(Diag.POLL_MS)
                    }
                }
                send()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: RuntimeException) {
                LinkLog.add(tr("сторож: дані для перевірки — помилка ", "guard: check data — error ") + e.javaClass.simpleName)
            }
        }, "guard-diag").start()
    }

    /** Раз на хвилину: «я працюю», якщо пора. («Не бачу батарею» кличе фон через [onNoData].) */
    private fun tick(ctx: Context, now: Long) {
        val st = GuardSettings(ctx)
        if (!st.enabled) return
        if (Guard.dailyDue(st.dailyDay, now, st.dailyHour)) {
            st.dailyDay = Guard.dayKey(now)
            queue(ctx, listOf(Guard.daily(seen(ctx), now)), Pic.All)
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

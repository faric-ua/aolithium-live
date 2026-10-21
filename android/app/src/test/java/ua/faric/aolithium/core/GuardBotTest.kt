package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T48: розмова людей з ботом сторожа — код доступу, кілька учасників, кожен вибирає своє. */
class GuardBotTest {
    private val code = "482913"
    private val now = 1_792_558_800_000L

    private fun input(members: List<GuardBot.Member> = emptyList(), tries: Map<Long, GuardBot.Tries> = emptyMap()) =
        GuardBot.Input(code, members, { "87 % · стоїть" }, now, tries)

    private fun text(chat: Long, t: String, name: String = "Олег") = Telegram.Update(1, chat, name, true, text = t)

    private fun sends(r: GuardBot.Result) = r.actions.filterIsInstance<GuardBot.Action.Send>()

    @Test fun stranger_without_code_gets_hello_only() {
        val r = GuardBot.handle(text(7, "/start"), input())
        assertTrue(r.members.isEmpty())
        assertTrue(sends(r).single().text.contains("код доступу"))
        val s = GuardBot.handle(text(7, "/status"), input())
        assertTrue("стан батареї чужим не кажемо", s.members.isEmpty() && !sends(s).single().text.contains("87 %"))
    }

    @Test fun join_with_start_code_or_plain_code() {
        val a = GuardBot.handle(text(7, "/start 482913"), input())
        assertEquals(listOf(7L), a.members.map { it.chatId })
        assertEquals(Guard.Kind.ALL, a.members[0].kinds)
        assertEquals("Олег під'єднався", a.note)
        // 10 видів + 2 ряди вигляду картинки (T59) + «Готово»
        assertEquals(Guard.Kind.entries.size + 2 + 1, sends(a).last().keyboard!!.size)
        val b = GuardBot.handle(text(8, "482 913", "Саня"), GuardBot.Input(code, a.members, { "" }, now))
        assertEquals(listOf(7L, 8L), b.members.map { it.chatId })
    }

    @Test fun wrong_codes_are_limited() {
        var tries = emptyMap<Long, GuardBot.Tries>()
        repeat(GuardBot.MAX_TRIES) {
            tries = GuardBot.handle(text(9, "111111"), input(tries = tries)).tries
        }
        val blocked = GuardBot.handle(text(9, code), input(tries = tries))
        assertTrue("навіть правильний — після 5 хибних чекає годину", blocked.members.isEmpty())
        assertTrue(sends(blocked).single().text.contains("годину"))
        val later = GuardBot.handle(text(9, code), GuardBot.Input(code, emptyList(), { "" }, now + GuardBot.TRIES_WINDOW_MS + 1, tries))
        assertEquals(1, later.members.size)
    }

    @Test fun empty_code_never_lets_in() {
        val r = GuardBot.handle(text(7, "/start"), GuardBot.Input("", emptyList(), { "" }, now))
        assertTrue(r.members.isEmpty())
    }

    @Test fun member_toggles_own_kinds_only() {
        val members = listOf(GuardBot.Member(7, "Олег"), GuardBot.Member(8, "Саня"))
        val tap = Telegram.Update(2, 8, "Саня", true, callbackId = "cb", callbackData = "k:step", messageId = 55)
        val r = GuardBot.handle(tap, input(members))
        assertTrue(Guard.Kind.STEP !in r.members.first { it.chatId == 8L }.kinds)
        assertTrue(Guard.Kind.STEP in r.members.first { it.chatId == 7L }.kinds)
        assertEquals(listOf(7L), GuardBot.recipients(r.members, Guard.Kind.STEP))
        assertTrue(r.actions.any { it is GuardBot.Action.Edit && it.messageId == 55L })
        assertTrue(r.actions.any { it is GuardBot.Action.Answer && it.text.startsWith("Вимкнено") })
    }

    private fun statuses(r: GuardBot.Result) = r.actions.filterIsInstance<GuardBot.Action.Status>()

    @Test fun status_and_stop() {
        val members = listOf(GuardBot.Member(7, "Олег", look = GuardPicture.Look.CELLS))
        val st = statuses(GuardBot.handle(text(7, "/status"), input(members))).single()
        assertEquals("87 % · стоїть", st.text)
        assertEquals("картинка — у вигляді учасника (T59)", GuardPicture.Look.CELLS, st.look)
        assertEquals(false, st.grid)
        assertEquals("87 % · стоїть", statuses(GuardBot.handle(text(7, "стан"), input(members))).single().text)
        val r = GuardBot.handle(text(7, "/stop"), input(members))
        assertTrue(r.members.isEmpty())
    }

    @Test fun picture_look_is_chosen_per_member() {
        val members = listOf(GuardBot.Member(7, "Олег"), GuardBot.Member(8, "Саня"))
        assertEquals(GuardPicture.DEFAULT, members[0].look)
        val tap = Telegram.Update(2, 8, "Саня", true, callbackId = "cb", callbackData = "l:widget", messageId = 56)
        val r = GuardBot.handle(tap, input(members))
        assertEquals(GuardPicture.Look.WIDGET, r.members.first { it.chatId == 8L }.look)
        assertEquals(GuardPicture.DEFAULT, r.members.first { it.chatId == 7L }.look)
        assertTrue(r.actions.any { it is GuardBot.Action.Answer && it.text == "Картинка: Віджет" })
        val edit = r.actions.filterIsInstance<GuardBot.Action.Edit>().single()
        assertTrue(edit.keyboard!!.flatten().any { it.text == "🔵 Віджет" })
        assertTrue(edit.keyboard!!.flatten().any { it.text == "⚪ Спідометр" })
    }

    @Test fun menu_gives_all_batteries_picture() {
        val members = listOf(GuardBot.Member(7, "Олег"))
        val menu = sends(GuardBot.handle(text(7, "/menu"), input(members))).single()
        assertTrue(menu.keyboard!!.flatten().map { it.data }.containsAll(listOf("all", "set")))
        val all = GuardBot.handle(Telegram.Update(3, 7, "Олег", true, callbackId = "cb", callbackData = "all", messageId = 9), input(members))
        assertTrue(statuses(all).single().grid)
        val stranger = GuardBot.handle(Telegram.Update(4, 99, "Хтось", true, callbackId = "cb", callbackData = "all"), input(members))
        assertTrue("чужим стану не показуємо", statuses(stranger).isEmpty())
    }

    @Test fun bot_added_to_group_by_hand_explains_how_to_start() {
        // T58 (faric 21.10 17:51): бота додали в групу вручну — раніше мовчав.
        val added = Telegram.Update(5, -100777, "Батареї", false, added = listOf("@AolithiumLive_bot"))
        val r = GuardBot.handle(added, GuardBot.Input(code, emptyList(), { "" }, now, botName = "@AolithiumLive_bot"))
        val hint = sends(r).single().text
        assertTrue(hint, hint.contains("/start@AolithiumLive_bot КОД"))
        assertTrue("код у групу не пишемо", !hint.contains(code))
        assertTrue(r.members.isEmpty())
        // додали іншого бота (мамин) — наш мовчить
        val other = Telegram.Update(6, -100777, "Батареї", false, added = listOf("@Aolithium_mom_bot"))
        assertTrue(GuardBot.handle(other, GuardBot.Input(code, emptyList(), { "" }, now, botName = "@AolithiumLive_bot")).actions.isEmpty())
        // група вже учасник — підказка не потрібна
        val member = GuardBot.handle(added, GuardBot.Input(code, listOf(GuardBot.Member(-100777, "Батареї")), { "" }, now, botName = "@AolithiumLive_bot"))
        assertTrue(member.actions.isEmpty())
        // потім у групі — команда з кодом: група стає учасником
        val join = GuardBot.handle(Telegram.Update(7, -100777, "Батареї", false, text = "/start@AolithiumLive_bot $code"),
            GuardBot.Input(code, emptyList(), { "" }, now, botName = "@AolithiumLive_bot"))
        assertEquals(listOf(-100777L), join.members.map { it.chatId })
    }

    @Test fun members_survive_save_and_load() {
        val m = listOf(GuardBot.Member(7, "Олег\tК", Guard.Kind.ALL - Guard.Kind.STEP, now, GuardPicture.Look.CELLS),
            GuardBot.Member(-100123, "Сім'я", setOf(Guard.Kind.OUTAGE)))
        val back = GuardBot.decode(GuardBot.encode(m))
        assertEquals(listOf(7L, -100123L), back.map { it.chatId })
        assertEquals("Олег К", back[0].name)
        assertEquals(m[0].kinds, back[0].kinds)
        assertEquals(setOf(Guard.Kind.OUTAGE), back[1].kinds)
        assertEquals(GuardPicture.Look.CELLS, back[0].look)
        // учасники з 0.4.13 (без вигляду) — вигляд за замовчуванням
        assertEquals(GuardPicture.DEFAULT, GuardBot.decode("7\tОлег\tprot,temp\t0").single().look)
    }

    @Test fun new_code_is_six_digits() {
        assertEquals("100000", GuardBot.newCode { 0.0 })
        assertEquals("999999", GuardBot.newCode { 0.9999999 })
        assertEquals(6, GuardBot.newCode { 0.5 }.length)
    }

    @Test fun diag_asks_for_a_file_only_from_members() {
        val members = listOf(GuardBot.Member(-100, "Aolithium_room"))
        val r = GuardBot.handle(Telegram.Update(4, -100, "Aolithium_room", false, text = "/diag@Aolithium_mom_bot"), input(members))
        assertEquals(listOf(GuardBot.Action.Diag(-100, "Aolithium_room")), r.actions)
        // Чужий (без коду) — лише привітання, жодних даних плати.
        val stranger = GuardBot.handle(text(55, "/diag"), input(members))
        assertTrue(stranger.actions.none { it is GuardBot.Action.Diag })
        assertTrue(GuardBot.commands.any { it.first == "diag" })
    }
}

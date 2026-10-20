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
        assertTrue(sends(a).last().keyboard!!.size == Guard.Kind.entries.size + 1)
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

    @Test fun status_and_stop() {
        val members = listOf(GuardBot.Member(7, "Олег"))
        assertEquals("87 % · стоїть", sends(GuardBot.handle(text(7, "/status"), input(members))).single().text)
        assertEquals("87 % · стоїть", sends(GuardBot.handle(text(7, "стан"), input(members))).single().text)
        val r = GuardBot.handle(text(7, "/stop"), input(members))
        assertTrue(r.members.isEmpty())
    }

    @Test fun members_survive_save_and_load() {
        val m = listOf(GuardBot.Member(7, "Олег\tК", Guard.Kind.ALL - Guard.Kind.STEP, now), GuardBot.Member(-100123, "Сім'я", setOf(Guard.Kind.OUTAGE)))
        val back = GuardBot.decode(GuardBot.encode(m))
        assertEquals(listOf(7L, -100123L), back.map { it.chatId })
        assertEquals("Олег К", back[0].name)
        assertEquals(m[0].kinds, back[0].kinds)
        assertEquals(setOf(Guard.Kind.OUTAGE), back[1].kinds)
    }

    @Test fun new_code_is_six_digits() {
        assertEquals("100000", GuardBot.newCode { 0.0 })
        assertEquals("999999", GuardBot.newCode { 0.9999999 })
        assertEquals(6, GuardBot.newCode { 0.5 }.length)
    }
}

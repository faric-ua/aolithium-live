package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T54: передати сторожа разом з людьми й кодом; T52: група стала супергрупою. */
class GuardTransferTest {
    private val token = "1234567890:AAH_fake-token-for-tests-only_abcdefgh"
    private val pack = GuardTransfer.Pack(
        token, "@AolithiumLive_bot", "375773",
        listOf(GuardBot.Member(42, "Олег", Guard.Kind.ALL - Guard.Kind.STEP, 5, GuardPicture.Look.WIDGET), GuardBot.Member(-100777, "Батареї родини", setOf(Guard.Kind.OUTAGE))),
        12,
    )

    @Test fun round_trip_keeps_everything() {
        assertEquals(pack, GuardTransfer.decode(GuardTransfer.encode(pack)))
    }

    @Test fun found_inside_a_forwarded_message_with_line_breaks() {
        val msg = GuardTransfer.message(pack)
        assertTrue(msg.contains("Нікому більше не пересилай"))
        val code = GuardTransfer.encode(pack)
        val mangled = "Пересланo від faric:\n" + msg.replace(code, code.substring(0, 30) + "\n " + code.substring(30)) + "\nдякую!"
        assertEquals(pack, GuardTransfer.decode(mangled))
    }

    @Test fun plain_token_or_junk_is_not_a_transfer() {
        assertNull(GuardTransfer.decode(token))
        assertNull(GuardTransfer.decode("AOLGUARD1:@@@"))
        assertNull(GuardTransfer.decode("AOLGUARD1:" + java.util.Base64.getUrlEncoder().encodeToString("{\"token\":\"bad\"}".toByteArray())))
    }

    @Test fun group_becomes_supergroup() {
        val members = listOf(GuardBot.Member(42, "Олег"), GuardBot.Member(-555, "Батареї"))
        val moved = GuardBot.migrate(members, -555, -1009876543210)
        assertEquals(listOf(42L, -1009876543210L), moved.map { it.chatId })
        assertEquals("Батареї", moved[1].name)
        val already = GuardBot.migrate(moved + GuardBot.Member(-555, "старий"), -555, -1009876543210)
        assertEquals("не двоїти", 2, already.size)
        val r = Telegram.reply("""{"ok":false,"error_code":400,"description":"Bad Request: group chat was upgraded to a supergroup chat","parameters":{"migrate_to_chat_id":-1009876543210}}""")
        assertEquals(-1009876543210L, r.migrateTo)
    }

    @Test fun group_name_is_the_group_title() {
        val json = """{"ok":true,"result":[{"update_id":7,"message":{"message_id":1,"from":{"id":42,"first_name":"Олег"},"chat":{"id":-555,"type":"group","title":"Батареї родини"},"text":"/start@AolithiumLive_bot 375773"}}]}"""
        val u = Telegram.updates(Telegram.reply(json)).single()
        assertEquals("Батареї родини", u.name)
        val r = GuardBot.handle(u, GuardBot.Input("375773", emptyList(), { "" }, 0L))
        assertEquals(listOf(-555L), r.members.map { it.chatId })
        assertTrue(GuardBot.isGroup(r.members[0]))
        assertTrue((r.actions.first() as GuardBot.Action.Send).text.contains("групу «Батареї родини»"))
    }
}

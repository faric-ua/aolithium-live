package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T48: розбір відповідей Telegram (зразки — з документації Bot API, числа вигадані). */
class TelegramTest {
    private val token = "1234567890:AAH_fake-token-for-tests-only_abcdefgh"

    @Test fun token_shape() {
        assertTrue(Telegram.tokenLooksRight(token))
        assertFalse(Telegram.tokenLooksRight("1234567890 AAH"))
        assertFalse(Telegram.tokenLooksRight("bot1234:short"))
        assertEquals(token, Telegram.cleanToken("  «$token»\n"))
        assertFalse("у журналі токена нема", Telegram.mask(token).contains("fake-token"))
    }

    @Test fun updates_message_and_button() {
        val json = """{"ok":true,"result":[
          {"update_id":100,"message":{"message_id":1,"from":{"id":42,"first_name":"Олег","last_name":"К"},"chat":{"id":42,"type":"private"},"date":1,"text":"/start 482913"}},
          {"update_id":101,"callback_query":{"id":"cb7","from":{"id":42,"first_name":"Олег"},"message":{"message_id":9,"chat":{"id":42,"type":"private"}},"data":"k:step"}},
          {"update_id":102,"my_chat_member":{"chat":{"id":42}}}
        ]}"""
        val ups = Telegram.updates(Telegram.reply(json))
        assertEquals(3, ups.size)
        assertEquals(Telegram.Update(100, 42, "Олег К", true, text = "/start 482913"), ups[0])
        assertEquals("cb7", ups[1].callbackId)
        assertEquals("k:step", ups[1].callbackData)
        assertEquals(9L, ups[1].messageId)
        assertEquals("інше — лише номер, щоб пропустити", 0L, ups[2].chatId)
        assertEquals(102L, ups[2].updateId)
    }

    @Test fun bot_added_to_group_or_group_created() {
        // T58: службові повідомлення — «додали учасників» і «групу створено» (тексту нема).
        val json = """{"ok":true,"result":[
          {"update_id":200,"message":{"message_id":3,"from":{"id":42,"first_name":"Олег"},"chat":{"id":-100777,"type":"supergroup","title":"Батареї"},"date":1,
            "new_chat_members":[{"id":7001,"is_bot":true,"first_name":"Aolithium","username":"AolithiumLive_bot"},{"id":7002,"is_bot":true,"username":"Aolithium_mom_bot"}]}},
          {"update_id":201,"message":{"message_id":1,"from":{"id":42,"first_name":"Олег"},"chat":{"id":-555,"type":"group","title":"Дім"},"date":1,"group_chat_created":true}}
        ]}"""
        val ups = Telegram.updates(Telegram.reply(json))
        assertEquals(listOf("@AolithiumLive_bot", "@Aolithium_mom_bot"), ups[0].added)
        assertEquals("Батареї", ups[0].name)
        assertFalse(ups[0].private)
        assertEquals(null, ups[0].text)
        assertTrue(ups[1].groupCreated)
    }

    @Test fun photo_multipart() {
        val fields = Telegram.photoFields(42, "🔌 Світло зникло\n🔋 Aolithium", listOf(listOf(Telegram.Button("📊", "all"))))
        assertEquals(listOf("chat_id", "caption", "reply_markup"), fields.map { it.first })
        assertTrue(Telegram.photoFields(1, "я".repeat(3000))[1].second.length <= GuardPicture.CAPTION_MAX)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val body = Telegram.multipart("BOUND", fields, "photo", "a.png", png)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n42\r\n"))
        assertTrue(text.contains("name=\"photo\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n\u0089PNG\r\n--BOUND--\r\n"))
        assertTrue("підпис — UTF-8", String(body, Charsets.UTF_8).contains("Світло зникло"))
    }

    @Test fun document_multipart_is_plain_text() {
        val body = Telegram.multipart("B", Telegram.photoFields(7, "📎"), "document", "aolithium-diag-20261022-0105.txt",
            "дані".toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.contains("name=\"document\"; filename=\"aolithium-diag-20261022-0105.txt\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nдані\r\n--B--"))
    }

    @Test fun errors() {
        val r = Telegram.reply("""{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""")
        assertFalse(r.ok)
        assertEquals(403, r.code)
        assertTrue(Telegram.goneForGood(r))
        assertFalse(Telegram.reply("<html>").ok)
        assertEquals("@aol_home_bot", Telegram.botName(Telegram.reply("""{"ok":true,"result":{"id":1,"is_bot":true,"username":"aol_home_bot"}}""")))
    }

    @Test fun bodies_are_valid_json() {
        val kb = listOf(listOf(Telegram.Button("\uD83D\uDD14 Захист", "k:prot")))
        val send = JsonTree.obj(JsonTree.parse(Telegram.sendBody(42, "Світло зникло", kb)))!!
        assertEquals(42L, send["chat_id"])
        val markup = JsonTree.obj(send["reply_markup"])!!
        val btn = JsonTree.obj(JsonTree.list(JsonTree.list(markup["inline_keyboard"])[0])[0])!!
        assertEquals("k:prot", btn["callback_data"])
        val long = JsonTree.obj(JsonTree.parse(Telegram.sendBody(1, "я".repeat(9000))))!!
        assertTrue((long["text"] as String).length <= Telegram.MAX_TEXT)
        assertEquals("https://api.telegram.org/bot$token/getMe", Telegram.url(token, "getMe"))
    }
}

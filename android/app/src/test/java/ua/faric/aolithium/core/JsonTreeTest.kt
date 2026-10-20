package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** T48: свій JSON для відповідей Telegram. */
class JsonTreeTest {
    @Test fun nested_objects_and_lists() {
        val v = JsonTree.obj(JsonTree.parse("""{"ok":true,"result":[{"update_id":5,"message":{"text":"hi","n":-3,"x":1.5}}],"z":null}"""))!!
        assertEquals(true, v["ok"])
        val first = JsonTree.obj(JsonTree.list(v["result"])[0])!!
        assertEquals(5L, first["update_id"])
        val msg = JsonTree.obj(first["message"])!!
        assertEquals("hi", msg["text"])
        assertEquals(-3L, msg["n"])
        assertEquals(1.5, msg["x"] as Double, 1e-9)
        assertNull(v["z"])
    }

    @Test fun escapes_and_ukrainian_and_emoji() {
        val v = JsonTree.parse("\"\\u041f\\u0440\\u0438\\u0432\\u0456\\u0442 \\ud83d\\udd0b \\\"q\\\" \\n\"")
        assertEquals("Привіт \uD83D\uDD0B \"q\" \n", v)
        assertEquals("Ї", JsonTree.parse("\"Ї\""))
    }

    @Test fun write_round_trip() {
        val m = mapOf("chat_id" to 123456789012L, "text" to "Світло зникло\n\"тест\"", "list" to listOf(1, true, null))
        val back = JsonTree.obj(JsonTree.parse(JsonTree.write(m)))!!
        assertEquals(123456789012L, back["chat_id"])
        assertEquals("Світло зникло\n\"тест\"", back["text"])
        assertEquals(listOf(1L, true, null), back["list"])
    }

    @Test fun broken_is_an_error_not_a_crash() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,2", "{\"a\":1}x", "\"\\u12\"", "tru")) {
            try {
                JsonTree.parse(bad)
                fail("мало впасти: $bad")
            } catch (e: IllegalArgumentException) {
                // так і треба
            }
        }
        assertNull(JsonTree.parseOrNull("{oops"))
    }

    @Test fun too_deep_is_refused() {
        val deep = "[".repeat(100) + "]".repeat(100)
        assertNull(JsonTree.parseOrNull(deep))
    }
}

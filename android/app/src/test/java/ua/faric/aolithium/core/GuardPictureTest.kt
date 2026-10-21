package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T59: картинка батареї в боті — вигляди, підпис, черга з картинками. */
class GuardPictureTest {
    @Test fun outbox_with_and_without_picture() {
        val items = listOf(
            GuardPicture.Out(7, "🔌 Світло зникло\n🔋 Aolithium\tтаб", "g-1792558800000-0-gauge.png"),
            GuardPicture.Out(-100777, "лише текст"),
        )
        val back = GuardPicture.decode(GuardPicture.encode(items))
        assertEquals("🔌 Світло зникло\n🔋 Aolithium таб", back[0].text)
        assertEquals("g-1792558800000-0-gauge.png", back[0].photo)
        assertNull(back[1].photo)
        // черга з 0.4.13 («id<TAB>текст») читається як була
        assertEquals(listOf(GuardPicture.Out(7, "рядок\nдругий")), GuardPicture.decode("7\tрядок другий"))
    }

    @Test fun only_own_picture_names() {
        assertTrue(GuardPicture.safeName(GuardPicture.fileName(1792558800000, 3, GuardPicture.Look.CELLS)))
        assertFalse(GuardPicture.safeName("../shared_prefs/guard.xml"))
        assertNull("чужий шлях у черзі — без картинки", GuardPicture.decode("7\tтекст\t../x.png").single().photo)
    }

    @Test fun long_text_goes_after_the_picture() {
        val short = GuardPicture.split("🟢 Сторож працює\n🔋 A: 87 %")
        assertEquals(null, short.followUp)
        val long = "🟢 Сторож працює\n" + "🔋 A: 87 % · стоїть\n".repeat(80)
        val sp = GuardPicture.split(long)
        assertEquals("🟢 Сторож працює", sp.caption)
        assertEquals(long, sp.followUp)
    }

    @Test fun looks_keyboard_marks_the_chosen_one() {
        val kb = GuardPicture.keyboard(GuardPicture.Look.CELLS)
        assertEquals(2, kb.size)
        val flat = kb.flatten()
        assertEquals(1, flat.count { it.text.startsWith("🔘") })
        assertEquals("l:cells", flat.first { it.text.startsWith("🔘") }.data)
        assertEquals(GuardPicture.Look.WIDGET, GuardPicture.Look.of("widget"))
        assertNull(GuardPicture.Look.of("щось"))
    }
}

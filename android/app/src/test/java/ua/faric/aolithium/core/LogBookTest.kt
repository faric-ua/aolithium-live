package ua.faric.aolithium.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T57: журнал зв'язку частинами — переживає перезапуск, найстаріші частини стираються, усе збирається в одне. */
class LogBookTest {
    private fun tmp(): File = Files.createTempDirectory("logbook").toFile()

    @Test fun splits_into_parts_and_keeps_only_the_newest() {
        val dir = tmp()
        val book = LogBook(dir, partLines = 3, keepParts = 2)
        for (i in 1..10) book.add("рядок $i")
        // 10 рядків по 3 → частини 1..4; лишились дві найсвіжіші
        assertEquals(listOf(3, 4), book.parts())
        assertEquals(listOf("рядок 9", "рядок 10"), book.recent(2))
        assertEquals((7..10).map { "рядок $it" }, book.recent(100))
        assertTrue(File(dir, "linklog-000004.txt").readLines().first().contains("попередня 3"))
    }

    @Test fun survives_restart_and_continues_the_same_part() {
        val dir = tmp()
        LogBook(dir, partLines = 5).apply { add("a"); add("b") }
        val again = LogBook(dir, partLines = 5)
        again.add("c")
        assertEquals(listOf(1), again.parts())
        assertEquals(listOf("a", "b", "c"), again.recent(10))
    }

    @Test fun all_is_newest_first_with_part_marks() {
        val dir = tmp()
        val book = LogBook(dir, partLines = 2)
        listOf("1", "2", "3").forEach { book.add(it) }
        val lines = book.all().lines()
        assertEquals("3", lines[0])
        assertTrue(lines[1].startsWith("# частина 2"))
        assertEquals(listOf("2", "1"), lines.subList(2, 4))
        assertTrue(lines[4].startsWith("# частина 1"))
        book.clear()
        assertEquals("", book.all())
        assertEquals(emptyList<Int>(), book.parts())
    }

    @Test fun line_breaks_do_not_break_the_file() {
        val book = LogBook(tmp())
        book.add("два\nрядки")
        assertEquals(listOf("два рядки"), book.recent(5))
    }
}

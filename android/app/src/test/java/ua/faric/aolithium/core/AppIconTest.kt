package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T63: значок на вибір — чотири псевдоніми, увімкнений один. */
class AppIconTest {
    private val v = AppIcon.Variant.entries

    @Test fun fresh_install_shows_the_classic_icon() {
        assertEquals(AppIcon.Variant.CLASSIC, AppIcon.current(emptyMap()))
        assertEquals(AppIcon.Variant.CLASSIC, AppIcon.current(v.associateWith { AppIcon.State.DEFAULT }))
    }

    @Test fun chosen_icon_is_current() {
        val s = mapOf(
            AppIcon.Variant.CLASSIC to AppIcon.State.DISABLED,
            AppIcon.Variant.RING to AppIcon.State.ENABLED,
            AppIcon.Variant.LEVEL to AppIcon.State.DEFAULT,
            AppIcon.Variant.PULSE to AppIcon.State.DISABLED,
        )
        assertEquals(AppIcon.Variant.RING, AppIcon.current(s))
    }

    @Test fun back_to_classic_after_a_change() {
        val s = mapOf(AppIcon.Variant.CLASSIC to AppIcon.State.ENABLED, AppIcon.Variant.PULSE to AppIcon.State.DISABLED)
        assertEquals(AppIcon.Variant.CLASSIC, AppIcon.current(s))
    }

    @Test fun interrupted_switch_trusts_the_new_one() {
        // Новий увімкнули, старий ще не встигли вимкнути.
        val s = mapOf(AppIcon.Variant.CLASSIC to AppIcon.State.DEFAULT, AppIcon.Variant.PULSE to AppIcon.State.ENABLED)
        assertEquals(AppIcon.Variant.PULSE, AppIcon.current(s))
    }

    @Test fun plan_enables_first_then_disables_the_rest() {
        val p = AppIcon.plan(AppIcon.Variant.LEVEL)
        assertEquals(AppIcon.Variant.LEVEL to true, p.first())
        assertEquals(4, p.size)
        assertTrue(p.drop(1).all { !it.second && it.first != AppIcon.Variant.LEVEL })
    }

    @Test fun four_variants_with_letters_and_own_aliases() {
        assertEquals(listOf("А", "Б", "В", "Г"), v.map { it.mark })
        assertEquals(4, v.map { it.alias }.toSet().size)
        assertTrue(v.all { it.alias.startsWith("ua.faric.aolithium.Icon") })
        assertEquals(AppIcon.Variant.RING, AppIcon.Variant.of("ring"))
        assertNull(AppIcon.Variant.of("щось"))
    }
}

package ua.faric.aolithium.core

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T48: що сторож шле в Telegram (faric 21.10 01:53). */
class GuardTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")
    // 21.10.2026 08:00:00 за Києвом (UTC+3)
    private val t0 = 1_792_558_800_000L
    private val min = 60_000L

    private fun snap(
        soc: Int = 80,
        current: Double = 0.0,
        cells: List<Int> = listOf(3330, 3331, 3330, 3332),
        temps: List<Double> = listOf(15.0, 15.2, 15.1),
        protections: List<Protection> = emptyList(),
        dischargeOn: Boolean = true,
    ) = BatterySnapshot(
        voltage = 13.3, current = current, soc = soc, remainAh = 92.3 * soc / 100.0, fullAh = 92.3, cycles = 123,
        cellsMv = cells, temperatures = temps, balancingCells = emptyList(), protections = protections,
        unknownProtectionBits = emptyList(), chargeOn = true, dischargeOn = dischargeOn, firmware = "2.3", made = null,
        model = "AL12-100-4S", errorCounts = null, cellCount = cells.size,
    )

    private fun run(vararg pts: Pair<Long, BatterySnapshot>, from: Guard.State = Guard.State()): Pair<Guard.State, List<Guard.Msg>> {
        var st = from
        val all = ArrayList<Guard.Msg>()
        for ((t, s) in pts) {
            val step = Guard.onSnapshot(st, s, t, "Aolithium", kyiv)
            st = step.state
            all += step.messages
        }
        return st to all
    }

    @Test fun every_ten_percent_down_while_discharging() {
        val pts = (0..30).map { i -> t0 + i * 5 * min to snap(soc = 95 - i, current = -20.0) }
        val (_, msgs) = run(*pts.toTypedArray())
        val steps = msgs.filter { it.kind == Guard.Kind.STEP }.map { it.title }
        // 95 → 65: про 90, 80, 70 (20 % і нижче — «мало заряду», не тут)
        assertEquals(listOf("Заряд 89 %", "Заряд 79 %", "Заряд 69 %"), steps)
        assertTrue(msgs.filter { it.kind == Guard.Kind.STEP }.all { it.text.contains("віддає") && it.battery == "Aolithium" })
    }

    @Test fun step_ignores_one_percent_noise() {
        val pts = listOf(70, 69, 70, 69, 71, 69, 70, 69).mapIndexed { i, soc -> t0 + i * 5 * min to snap(soc = soc, current = -5.0) }
        val (_, msgs) = run(*pts.toTypedArray())
        assertEquals(1, msgs.count { it.kind == Guard.Kind.STEP })
    }

    @Test fun step_not_while_charging_and_rearms_after_charge() {
        val (st, msgs) = run(
            t0 to snap(soc = 55, current = -10.0),
            t0 + 5 * min to snap(soc = 49, current = -10.0), // −10 % → 40-ві
            t0 + 10 * min to snap(soc = 75, current = 30.0), // зарядили
            t0 + 15 * min to snap(soc = 69, current = -10.0), // знову вниз
        )
        assertEquals(listOf("Заряд 49 %", "Заряд 69 %"), msgs.filter { it.kind == Guard.Kind.STEP }.map { it.title })
        assertEquals(60, st.step)
    }

    @Test fun low_and_step_do_not_double_at_twenty() {
        val (_, msgs) = run(t0 to snap(soc = 25, current = -10.0), t0 + 5 * min to snap(soc = 19, current = -10.0))
        val about = msgs.filter { it.kind == Guard.Kind.LOW || it.kind == Guard.Kind.STEP }
        assertEquals("одне «мало заряду», без «Заряд 19 %» поверх", 1, about.size)
        assertEquals(Guard.Kind.LOW, about[0].kind)
    }

    @Test fun empty_once_then_rearms() {
        val (st, msgs) = run(
            t0 to snap(soc = 3, current = -5.0),
            t0 + 5 * min to snap(soc = 1, current = -5.0),
            t0 + 10 * min to snap(soc = 0, current = -1.0),
        )
        assertEquals(1, msgs.count { it.title.startsWith("Батарея розрядилась") })
        assertTrue(st.empty)
        val (st2, _) = run(t0 + 60 * min to snap(soc = 12, current = 20.0), from = st)
        assertFalse(st2.empty)
    }

    @Test fun outage_and_restore_go_to_telegram() {
        val (_, msgs) = run(
            t0 to snap(current = 0.0),
            t0 + 1 * min to snap(current = -15.0),
            t0 + 5 * min to snap(current = -15.0),
            t0 + 60 * min to snap(current = 10.0),
            t0 + 65 * min to snap(current = 10.0),
        )
        assertEquals(listOf("Світло зникло", "Світло повернулось"), msgs.filter { it.kind == Guard.Kind.OUTAGE }.map { it.title })
    }

    @Test fun temperature_jump_once_with_quiet_time() {
        val (st, msgs) = run(
            t0 to snap(temps = listOf(20.0)),
            t0 + 5 * min to snap(temps = listOf(22.0)),
            t0 + 10 * min to snap(temps = listOf(26.5)),
            t0 + 15 * min to snap(temps = listOf(33.0)),
        )
        val jumps = msgs.filter { it.kind == Guard.Kind.JUMP }
        assertEquals("один раз, далі — тиша 30 хв", 1, jumps.size)
        assertTrue(jumps[0].text, jumps[0].text.contains("температура"))
        assertEquals(t0 + 10 * min, st.jumpAt)
    }

    @Test fun slow_warming_is_not_a_jump() {
        val pts = (0..24).map { i -> t0 + i * 5 * min to snap(temps = listOf(15.0 + i * 0.5)) } // +0,5 °C / 5 хв
        val (_, msgs) = run(*pts.toTypedArray())
        assertTrue(msgs.none { it.kind == Guard.Kind.JUMP })
    }

    @Test fun cell_difference_jump() {
        val (_, msgs) = run(
            t0 to snap(cells = listOf(3330, 3331, 3330, 3332)),
            t0 + 5 * min to snap(cells = listOf(3330, 3331, 3270, 3332)),
        )
        assertTrue(msgs.any { it.kind == Guard.Kind.JUMP && it.text.contains("різниця комірок") })
    }

    @Test fun old_baseline_is_not_compared() {
        val (_, msgs) = run(t0 to snap(temps = listOf(10.0)), t0 + 3 * 60 * min to snap(temps = listOf(25.0)))
        assertTrue("3 год — це не стрибок", msgs.none { it.kind == Guard.Kind.JUMP })
    }

    @Test fun no_data_after_half_an_hour_and_back() {
        val none = Guard.onNoData(Guard.State(), t0, t0 + 20 * min, "A", kyiv)
        assertTrue(none.messages.isEmpty())
        val gone = Guard.onNoData(Guard.State(), t0, t0 + 31 * min, "A", kyiv)
        assertEquals("Сторож не бачить батарею з 08:00", gone.messages.single().title)
        assertTrue(Guard.onNoData(gone.state, t0, t0 + 90 * min, "A", kyiv).messages.isEmpty())
        val back = Guard.onSnapshot(gone.state, snap(), t0 + 95 * min, "A", kyiv)
        assertEquals("Знову бачу батарею", back.messages.first().title)
    }

    @Test fun no_data_pairs_are_quieter_at_the_edge_of_range() {
        // T56: на межі дальності батарея то зникає, то з'являється — не сипати парами «не бачу / знову бачу».
        val gone = Guard.onNoData(Guard.State(), t0, t0 + 31 * min, "A", kyiv)
        val back = Guard.onSnapshot(gone.state, snap(), t0 + 50 * min, "A", kyiv)
        val again = back.messages.first()
        assertTrue(again.text, again.text.startsWith("не бачив 50 хв · "))
        // зникла знову одразу — 30 хв без даних минуло, але після «знову бачу» лише 40 хв: мовчимо
        assertTrue(Guard.onNoData(back.state, t0 + 51 * min, t0 + 90 * min, "A", kyiv).messages.isEmpty())
        // через годину після «знову бачу» — кажемо
        val later = Guard.onNoData(back.state, t0 + 51 * min, t0 + 111 * min, "A", kyiv)
        assertEquals("Сторож не бачить батарею з 08:51", later.messages.single().title)
        assertEquals(later.state, Guard.State.decode(later.state.encode()))
    }

    @Test fun turned_on_after_midnight_still_says_daily_this_morning() {
        // T56: 21.10 сторожа ввімкнули о 02:56 — «я працюю» мало прийти о 9:00 того ж ранку.
        val at0256 = t0 - 5 * 60 * min - 4 * min // 08:00 − 5 год 04 хв
        val day = Guard.dayOnTurnOn(at0256, 9, kyiv)
        assertEquals(0, day)
        assertTrue("о 09:01 того ж дня — пора", Guard.dailyDue(day, t0 + 61 * min, 9, kyiv))
        // увімкнули о 10:00 — сьогоднішня 9:00 минула, перше — завтра
        val day2 = Guard.dayOnTurnOn(t0 + 120 * min, 9, kyiv)
        assertEquals(20261021, day2)
        assertFalse(Guard.dailyDue(day2, t0 + 180 * min, 9, kyiv))
    }

    @Test fun daily_once_a_day_from_the_hour() {
        val today = Guard.dayKey(t0, kyiv)
        assertEquals(20261021, today)
        assertFalse("о 08:00 ще рано (година 9)", Guard.dailyDue(20261020, t0, 9, kyiv))
        assertTrue(Guard.dailyDue(20261020, t0 + 61 * min, 9, kyiv))
        assertFalse("сьогодні вже слали", Guard.dailyDue(today, t0 + 61 * min, 9, kyiv))
        val msg = Guard.daily(listOf(Guard.Seen("Aolithium", snap(soc = 87), t0 + 58 * min)), t0 + 61 * min, kyiv)
        assertEquals(Guard.Kind.DAILY, msg.kind)
        assertTrue(msg.text, msg.text.contains("87 %") && msg.text.contains("08:58"))
        val stale = Guard.status(listOf(Guard.Seen("Aolithium", snap(soc = 40), t0 - 120 * min)), t0, kyiv)
        assertTrue(stale, stale.contains("не бачу з 06:00"))
    }

    @Test fun state_survives_save_and_load() {
        val (st, _) = run(t0 to snap(soc = 55, current = -10.0, temps = listOf(21.5)), t0 + 2 * min to snap(soc = 49, current = -10.0))
        assertEquals(st, Guard.State.decode(st.encode()))
        assertEquals(Guard.State(), Guard.State.decode("сміття"))
    }

    @Test fun render_has_icon_battery_and_text() {
        val r = Guard.Msg(Guard.Kind.OUTAGE, "Світло зникло", "Батарея віддає 320 Вт", "Aolithium").render()
        assertEquals("🔌 Світло зникло\n🔋 Aolithium\nБатарея віддає 320 Вт", r)
    }

    @Test fun no_data_names_the_reason() {
        // T81: у повідомленні сторожа — конкретна причина
        val gone = Guard.onNoData(Guard.State(), t0, t0 + 31 * min, "A", kyiv, reason = Background.Skip.localize("WEAK:-94"))
        val m = gone.messages.single()
        assertTrue(m.text, m.text.startsWith("Причина: батарею видно, але сигнал слабкий (-94 dBm)"))
    }
}

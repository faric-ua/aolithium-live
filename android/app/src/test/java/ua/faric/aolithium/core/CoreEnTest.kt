package ua.faric.aolithium.core

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T12: англійською — рядок «Зв'язок», сповіщення, віджет, фон, захисти. Без жодної кирилиці. */
class CoreEnTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")
    // 19.10.2026 19:40:00 за Києвом
    private val t0 = 1_792_428_000_000L
    private val min = 60_000L
    private val cyr = Regex("[А-Яа-яІіЇїЄєҐґ]")

    @Before fun en() {
        Lang.current = Lang.Code.EN
    }

    @After fun uk() {
        Lang.current = Lang.Code.UK
    }

    private fun noCyr(vararg s: String) {
        for (x in s) assertFalse("кирилиця в «$x»", cyr.containsMatchIn(x))
    }

    private fun snap(
        current: Double = 0.0,
        soc: Int = 80,
        cells: List<Int> = listOf(3330, 3331, 3330, 3332),
        temps: List<Double> = listOf(15.0),
        protections: List<Protection> = emptyList(),
        chargeOn: Boolean = true,
        dischargeOn: Boolean = true,
        unknownBits: List<Int> = emptyList(),
    ) = BatterySnapshot(
        voltage = 13.3, current = current, soc = soc, remainAh = 92.3 * soc / 100.0, fullAh = 92.3, cycles = 123,
        cellsMv = cells, temperatures = temps, balancingCells = emptyList(), protections = protections,
        unknownProtectionBits = unknownBits, chargeOn = chargeOn, dischargeOn = dischargeOn, firmware = "2.3", made = null,
        model = "AL12-100-4S", errorCounts = null, cellCount = cells.size,
    )

    private fun events(vararg pts: Pair<Long, BatterySnapshot>): List<Alerts.Event> {
        var st = Alerts.State()
        val all = ArrayList<Alerts.Event>()
        for ((t, s) in pts) {
            val step = Alerts.onSnapshot(st, s, t, kyiv)
            st = step.state
            all += step.events
        }
        return all
    }

    // ---------- LinkLine ----------

    @Test fun link_line_every_state_is_english() {
        val now = 1_000_000_000L
        for (st in LinkLine.State.entries) for (live in listOf(0L, now - 5_000)) for (saved in listOf(0L, now - 480_000)) {
            // rssi не даємо: «-90 dBm, слабкий» — з Signal.kt (інша частина перекладу).
            val m = LinkLine.build(st, attempt = 2, ofAttempts = 3, message = "", liveAt = live,
                liveStamp = "10/19 22:11", savedAt = saved, savedStamp = "10/19 22:04", now = now)
            noCyr(m.pill, m.detail, m.second)
        }
        val lost = LinkLine.build(LinkLine.State.LOST, attempt = 2, ofAttempts = 3, savedAt = now - 480_000,
            savedStamp = "10/19 22:04", now = now)
        assertEquals("Connection lost", lost.pill)
        assertEquals("retry 2 of 3", lost.detail)
        assertEquals("below — data from 10/19 22:04 (8 min ago)", lost.second)
        assertEquals("Connected", LinkLine.pillOf(LinkLine.State.LIVE))
        assertEquals("updated just now", LinkLine.build(LinkLine.State.LIVE, liveAt = now, now = now).second)
        assertEquals("no data yet — dashes instead of numbers", LinkLine.build(LinkLine.State.FAILED, now = now).second)
    }

    // ---------- Alerts ----------

    @Test fun alert_kinds_switch_language_without_restart() {
        for (k in Alerts.Kind.entries) noCyr(k.title, k.hint)
        assertEquals("Low charge", Alerts.Kind.LOW.title)
        Lang.current = Lang.Code.UK
        assertEquals("Мало заряду", Alerts.Kind.LOW.title)
        Lang.current = Lang.Code.EN
        assertEquals("Low charge", Alerts.Kind.LOW.title)
    }

    @Test fun outage_and_return_are_english() {
        // Знімки раз на 5 хв: годину віддає, потім заряджається.
        val pts = (0..12).map { i -> t0 + i * 5 * min to snap(current = -24.0, soc = 85 - i) } +
            listOf(t0 + 65 * min to snap(current = 10.0, soc = 70), t0 + 70 * min to snap(current = 10.0, soc = 71))
        val ev = events(*pts.toTypedArray())
        assertEquals(listOf("Power outage", "Power restored"), ev.map { it.title })
        assertTrue(ev[0].text, ev[0].text.startsWith("Battery discharging at 319 W · 84 %"))
        assertTrue(ev[1].text, ev[1].text.startsWith("Gone for 1 h 5 min · used 85 → 70 % · battery is charging"))
        for (e in ev) noCyr(e.title, e.text)
    }

    @Test fun low_full_protection_cells_temperature_are_english() {
        val low = events(t0 to snap(current = -24.0, soc = 30), t0 + 5 * min to snap(current = -24.0, soc = 19))
        assertEquals("Low charge: 19 %", low.single { it.kind == Alerts.Kind.LOW }.title)
        val full = events(t0 to snap(current = 10.0, soc = 96), t0 + 5 * min to snap(current = 0.0, soc = 100, chargeOn = false))
        assertEquals("Battery full — 100 %", full.single { it.kind == Alerts.Kind.FULL }.title)
        val prot = events(t0 to snap(soc = 50, protections = listOf(Protection.CHARGE_COLD), chargeOn = false))
        assertEquals("BMS turned off charging", prot.single().title)
        val dsg = Alerts.protectionEvent(snap(soc = 3, protections = listOf(Protection.CELL_UNDER), dischargeOn = false),
            setOf(Alerts.DSG_OFF, Protection.CELL_UNDER.name))
        assertTrue(dsg.text, dsg.text.startsWith("The battery gives no power now. A cell discharged"))
        val cells = events(t0 to snap(soc = 50, cells = listOf(3300, 3301, 3300, 3340)))
        assertEquals("Large cell difference: 40 mV", cells.single().title)
        assertEquals("No. 4 is higher than others at 50 %. See \"Cells\".", cells.single().text)
        val cold = events(t0 to snap(temps = listOf(-2.0)))
        assertEquals("Cold: -2.0 °C", cold.single().title)
        val noData = Alerts.onNoData(Alerts.State(seen = true, lastT = t0), t0, t0 + 61 * min, kyiv).events.single()
        assertEquals("No data since 19:40", noData.title)
        for (e in low + full + prot + listOf(dsg) + cells + cold + listOf(noData)) noCyr(e.title, e.text)
        noCyr(Alerts.lowText(snap(current = -24.0, soc = 4), 5), Alerts.lowText(snap(current = -24.0, soc = 9), 10),
            Alerts.lowText(snap(soc = 9), 10))
    }

    // ---------- WidgetModel ----------

    @Test fun widget_is_english_in_every_state() {
        val models = listOf(
            WidgetModel.of("A", null, null, t0, kyiv),
            WidgetModel.of("A", snap(current = -24.0, soc = 64), t0, t0 + 3 * min, kyiv),
            WidgetModel.of("A", snap(current = -10.0, soc = 15), t0, t0, kyiv),
            WidgetModel.of("A", snap(current = 46.0, soc = 42), t0, t0, kyiv),
            WidgetModel.of("A", snap(current = 0.0, soc = 100), t0, t0, kyiv),
            WidgetModel.of("A", snap(current = 0.0, soc = 50), t0, t0, kyiv),
            WidgetModel.of("A", snap(current = -24.0, soc = 85), t0, t0 + 125 * min, kyiv),
        )
        for (m in models) noCyr(m.percent, m.ringNote, m.power, m.status, m.line1, m.line2, m.small)
        val d = models[1]
        assertEquals("59.1 Ah", d.ringNote)
        assertEquals("319 W", d.power)
        assertEquals("discharging — no power", d.status)
        assertEquals("lasts ≈ 1 h 42 min to 20 %", d.line1)
        assertEquals("saved at 19:40", d.line2)
        assertEquals("until full ≈ 1 h 10 min · +50 % per h", models[3].line1)
        assertEquals("full · 19:40", models[4].small)
        assertEquals("no data since 19:40", models[6].small)
    }

    // ---------- фон, захисти, демо ----------

    @Test fun background_note_is_saved_as_key_and_shown_in_current_language() {
        val r = Background.Result(t0, ok = false, note = Background.Skip.BLUETOOTH_OFF.name)
        assertEquals("19:40 — Bluetooth is off", Background.status(r, kyiv))
        Lang.current = Lang.Code.UK
        assertEquals("19:40 — Bluetooth вимкнено", Background.status(r, kyiv))
        Lang.current = Lang.Code.EN
        assertEquals("A: no connection — battery far away or used by another phone",
            Background.line("A", Background.Result(t0, ok = false), kyiv, withTime = false))
        assertEquals("A: 64 % · discharging 4.10 A", Background.line("A", Background.Result(t0, true, 64, -4.1), kyiv, withTime = false))
        assertEquals("A: waiting for the first record", Background.line("A", null))
        // Старий запис (до T12) — українським текстом: показуємо як є.
        assertEquals("19:40 — Bluetooth вимкнено", Background.status(Background.Result(t0, false, note = "Bluetooth вимкнено"), kyiv))
    }

    @Test fun protections_and_enums_are_english() {
        for (p in Protection.entries) noCyr(p.title, p.explain)
        for (k in BmsMatch.Kind.entries) noCyr(k.title)
        for (s in DemoFrames.Scene.entries) noCyr(s.title)
        for (v in BatterySnapshot.Verdict.entries) noCyr(v.title)
        for (n in JbdParser.ERROR_COUNT_NAMES) noCyr(JbdParser.counterTitle(n))
        assertEquals("Too cold to charge", JbdParser.counterTitle("Холодно для заряду"))
        Lang.current = Lang.Code.UK
        assertEquals("Холодно для заряду", JbdParser.counterTitle("Холодно для заряду"))
        assertEquals("Холодно для заряду", Protection.CHARGE_COLD.title)
    }
}

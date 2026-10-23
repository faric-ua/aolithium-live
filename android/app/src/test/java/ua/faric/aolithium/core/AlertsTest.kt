package ua.faric.aolithium.core

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")
    // 19.10.2026 19:40:00 за Києвом (UTC+3)
    private val t0 = 1_792_428_000_000L
    private val min = 60_000L

    private fun snap(
        current: Double = 0.0,
        soc: Int = 80,
        cells: List<Int> = listOf(3330, 3331, 3330, 3332),
        temps: List<Double> = listOf(15.0, 15.2, 15.1),
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

    /** Прогнати знімки по черзі; повертає стан і всі події. */
    private fun run(vararg pts: Pair<Long, BatterySnapshot>, from: Alerts.State = Alerts.State()): Pair<Alerts.State, List<Alerts.Event>> {
        var st = from
        val all = ArrayList<Alerts.Event>()
        for ((t, s) in pts) {
            val step = Alerts.onSnapshot(st, s, t, kyiv)
            st = step.state
            all += step.events
        }
        return st to all
    }

    private fun kinds(ev: List<Alerts.Event>) = ev.map { it.kind }

    // ---------- світло ----------

    @Test
    fun outageNeedsTwoReadingsThreeMinutesApart() {
        // Фон читає раз на 5 хв: перший розряд — лише підозра, другий — «світло зникло».
        val (st1, ev1) = run(t0 to snap(current = -24.0, soc = 85))
        assertTrue(ev1.isEmpty())
        assertFalse(st1.outage)
        val (st2, ev2) = run(t0 + 5 * min to snap(current = -24.0, soc = 84), from = st1)
        assertEquals(listOf(Alerts.Kind.OUTAGE), kinds(ev2))
        assertEquals("Світло зникло", ev2[0].title)
        assertTrue(ev2[0].text, ev2[0].text.startsWith("Батарея віддає 319 Вт · 84 %"))
        assertTrue(ev2[0].text.contains("до 20 %"))
        assertTrue(st2.outage)
        assertEquals(t0, st2.outStart)
        assertEquals(85, st2.outSoc)
    }

    @Test
    fun shortDischargeIsNotAnOutage() {
        val (st, ev) = run(
            t0 to snap(current = -24.0),
            t0 + min to snap(current = -24.0),
            t0 + 2 * min to snap(current = 0.0),
            t0 + 4 * min to snap(current = -24.0),
        )
        assertTrue(ev.isEmpty())
        assertFalse(st.outage)
    }

    @Test
    fun lightBackReportsDurationAndPercent() {
        // Фон пише раз на 5 хв: 2 год 15 хв розряду (85 → 62 %), потім заряд.
        val dis = (0..27).map { k -> t0 + k * 5 * min to snap(current = -24.0, soc = 85 - k * 23 / 27) }
        val (st, ev) = run(
            *dis.toTypedArray(),
            t0 + 140 * min to snap(current = 30.0, soc = 62),
            t0 + 145 * min to snap(current = 30.0, soc = 64),
        )
        assertEquals(listOf(Alerts.Kind.OUTAGE, Alerts.Kind.OUTAGE), kinds(ev))
        assertEquals("Світло повернулось", ev[1].title)
        assertEquals("О 22:00 · не було 2 год 20 хв (з 19:40) · заряд 85 → 62 % · батарея заряджається", ev[1].text)
        assertFalse(st.outage)
    }

    @Test
    fun lightBackAfterPhoneWasAwayIsHonest() {
        val (_, ev) = run(
            t0 to snap(current = -24.0, soc = 85),
            t0 + 5 * min to snap(current = -24.0, soc = 84),
            // телефон пішов далеко на 1,5 години (менше за OUTAGE_FORGET_MS — відключення пам'ятаємо)
            t0 + 95 * min to snap(current = 0.0, soc = 40),
            t0 + 100 * min to snap(current = 0.0, soc = 40),
        )
        assertEquals("Світло повернулось", ev.last().title)
        assertEquals("О 21:15 · зникло о 19:40; поки телефон був далеко, даних нема — тривалість приблизно 1 год 35 хв · " +
            "заряд 85 → 40 % · батарея стоїть", ev.last().text)
    }

    /**
     * T78 (мамин сторож 23.10): «світла нема» з учорашніх 17:03, телефон далеко всю ніч, сьогодні о 14:40 бачить розряд,
     * о 15:10 — заряд. Раніше: «Відключення почалось о 17:03…» о 15:11. Тепер: старе забули, «Світло зникло · помітив о
     * 14:40», «Світло повернулось · О 15:10 · не було щонайменше 30 хв».
     */
    @Test
    fun longGapForgetsOldOutageAndSaysWhenNoticed() {
        // 22.10.2026 17:03 за Києвом
        val y = t0 + (3 * 24 * 60 - 2 * 60 - 37) * min
        val (st1, ev1) = run(
            y to snap(current = -24.0, soc = 100),
            y + 5 * min to snap(current = -24.0, soc = 100),
        )
        assertEquals(listOf("Світло зникло"), ev1.map { it.title })
        // телефон далеко до завтра; 23.10 14:40 — знову бачить розряд
        val d = y + (21 * 60 + 37) * min
        val (st2, ev2) = run(
            d to snap(current = -4.7, soc = 98),
            *(1..5).map { k -> d + k * 5 * min to snap(current = -5.2, soc = 98) }.toTypedArray(),
            d + 30 * min to snap(current = 12.8, soc = 98),
            d + 35 * min to snap(current = 12.8, soc = 98),
            from = st1,
        )
        assertEquals(listOf("Світло зникло", "Світло повернулось"), ev2.map { it.title })
        assertTrue(ev2[0].text, ev2[0].text.endsWith(" · помітив о 14:40; до того телефон не бачив батарею з 22.10, 17:08"))
        assertEquals("О 15:10 · не було щонайменше 30 хв (помітив о 14:40; раніше телефон був далеко) · заряд 98 → 98 % · " +
            "батарея заряджається", ev2[1].text)
        assertFalse(st2.outage)
        assertFalse(ev2.any { it.text.contains("17:03") })
    }

    @Test
    fun timeOfAnotherDayHasDate() {
        assertEquals("19:40", Alerts.at(t0, t0 + 60 * min, kyiv))
        assertEquals("19.10, 19:40", Alerts.at(t0, t0 + 24 * 60 * min, kyiv))
    }

    @Test
    fun describeTellsWhatTheGuardThinks() {
        val st = Alerts.State(seen = true, lastT = t0 + 60 * min, outage = true, outStart = t0, outSoc = 85, outGap = true)
        assertEquals("останній знімок 20:40 · світла нема з 19:40 (заряд тоді 85 %) · був пропуск у даних",
            Alerts.describe(st, t0 + 61 * min, kyiv))
        assertEquals("ще не бачив жодного знімка", Alerts.describe(Alerts.State(), t0, kyiv))
    }

    @Test
    fun gapResetsSuspicion() {
        // Розряд, потім телефон пропав на 30 хв, потім знову розряд — підозра починається заново.
        val (st, ev) = run(
            t0 to snap(current = -24.0),
            t0 + 30 * min to snap(current = -24.0),
        )
        assertTrue(ev.isEmpty())
        assertFalse(st.outage)
    }

    // ---------- мало заряду ----------

    @Test
    fun lowSocOncePerThresholdWithReset() {
        val (st, ev) = run(
            t0 to snap(current = -24.0, soc = 22),
            t0 + 5 * min to snap(current = -24.0, soc = 20),
            t0 + 10 * min to snap(current = -24.0, soc = 19),
            t0 + 15 * min to snap(current = -24.0, soc = 18),
            t0 + 60 * min to snap(current = -24.0, soc = 10),
        )
        val low = ev.filter { it.kind == Alerts.Kind.LOW }
        assertEquals(listOf("Мало заряду: 20 %", "Мало заряду: 10 %"), low.map { it.title })
        assertTrue(low[0].text, low[0].text.contains("до 10 % ≈"))
        assertEquals(10, st.low)
        // Генератор підняв до 14 % — 10 % ще «сказано»; до 16 % — знову 10 % можна, 20 % — ні.
        val (st2, ev2) = run(t0 + 90 * min to snap(current = 30.0, soc = 16), from = st)
        assertTrue(ev2.none { it.kind == Alerts.Kind.LOW })
        assertEquals(20, st2.low)
        val (_, ev3) = run(t0 + 200 * min to snap(current = -24.0, soc = 10), from = st2)
        assertEquals("Мало заряду: 10 %", ev3.single { it.kind == Alerts.Kind.LOW }.title)
    }

    @Test
    fun lowSocSkippedWhileCharging() {
        val (st, ev) = run(t0 to snap(current = 30.0, soc = 15))
        assertTrue(ev.isEmpty())
        assertEquals(0, st.low)
    }

    @Test
    fun lowestThresholdSaysChargeNow() {
        val (_, ev) = run(t0 to snap(current = -24.0, soc = 4))
        assertEquals("Мало заряду: 4 %", ev.single().title)
        assertTrue(ev.single().text, ev.single().text.startsWith("Нижче 5 % не варто"))
    }

    // ---------- повна ----------

    @Test
    fun fullOnceAndNotOnFirstReading() {
        val (st1, ev1) = run(t0 to snap(current = 0.0, soc = 100))
        assertTrue("перший знімок — лише запам'ятати", ev1.isEmpty())
        assertTrue(st1.full)
        val (st2, ev2) = run(
            t0 to snap(current = 20.0, soc = 97),
            t0 + 5 * min to snap(current = 0.0, soc = 100, protections = listOf(Protection.CELL_OVER), chargeOn = false),
            t0 + 10 * min to snap(current = 0.0, soc = 100),
            t0 + 60 * min to snap(current = 0.0, soc = 99),
        )
        assertEquals(listOf(Alerts.Kind.FULL), kinds(ev2))
        assertEquals("Батарея повна — 100 %", ev2[0].title)
        assertTrue(ev2[0].text.startsWith("Плата сама зупинила заряд"))
        // Кінець заряду — не тривога «захист».
        assertTrue(ev2.none { it.kind == Alerts.Kind.PROTECTION })
        assertTrue(st2.full)
        val (st3, _) = run(t0 + 600 * min to snap(current = -10.0, soc = 95), from = st2)
        assertFalse(st3.full)
    }

    // ---------- захист ----------

    @Test
    fun protectionOnceThenCleared() {
        val cold = snap(current = 0.0, soc = 60, protections = listOf(Protection.CHARGE_COLD), chargeOn = false)
        val (st, ev) = run(
            t0 to cold,
            t0 + 5 * min to cold,
            t0 + 10 * min to snap(current = 5.0, soc = 60),
        )
        assertEquals(listOf(Alerts.Kind.PROTECTION, Alerts.Kind.PROTECTION), kinds(ev))
        assertEquals("Плата вимкнула заряд", ev[0].title)
        assertTrue(ev[0].text, ev[0].text.contains("Літій-залізо-фосфат"))
        assertEquals("Захист знято", ev[1].title)
        assertTrue(st.prot.isEmpty())
    }

    @Test
    fun dischargeOffSaysBatteryGivesNothing() {
        val (_, ev) = run(t0 to snap(soc = 3, current = 0.0, protections = listOf(Protection.CELL_UNDER), dischargeOn = false))
        val p = ev.single { it.kind == Alerts.Kind.PROTECTION }
        assertEquals("Плата вимкнула розряд", p.title)
        assertTrue(p.text.startsWith("Батарея зараз не віддає енергію."))
    }

    @Test
    fun newProtectionWhileAnotherActiveIsAnnounced() {
        val a = snap(protections = listOf(Protection.CHARGE_OVERCURRENT))
        val b = snap(protections = listOf(Protection.CHARGE_OVERCURRENT, Protection.CHARGE_HOT))
        val (_, ev) = run(t0 to a, t0 + min to a, t0 + 2 * min to b)
        assertEquals(2, ev.count { it.kind == Alerts.Kind.PROTECTION })
        assertTrue(ev.last().text.contains("Завеликий струм заряду, Гаряче для заряду"))
    }

    @Test
    fun unknownBitIsShownNotHidden() {
        val (_, ev) = run(t0 to snap(unknownBits = listOf(14)))
        assertEquals("Спрацював захист плати", ev.single().title)
        assertTrue(ev.single().text.contains("біт 14"))
    }

    // ---------- комірки ----------

    @Test
    fun cellsMidSocFrom30mvAndOnce() {
        val bad = snap(soc = 60, cells = listOf(3300, 3302, 3268, 3301))
        val (st, ev) = run(t0 to bad, t0 + 5 * min to bad)
        assertEquals("Велика різниця комірок: 34 мВ", ev.single().title)
        assertEquals("№3 нижча за інші при 60 %. Подивись «Комірки».", ev.single().text)
        assertTrue(st.cells)
        val (st2, ev2) = run(t0 + 60 * min to snap(soc = 60, cells = listOf(3300, 3302, 3290, 3301)), from = st)
        assertTrue(ev2.isEmpty())
        assertFalse(st2.cells)
    }

    @Test
    fun cellsAtTopOfChargeAreNormal() {
        // Наприкінці заряду комірки розходяться — так буває, сповіщення не треба.
        val (_, ev) = run(t0 to snap(soc = 98, current = 10.0, cells = listOf(3450, 3480, 3390, 3600)))
        assertTrue(ev.none { it.kind == Alerts.Kind.CELLS })
        // А при 92 % — 100 мВ уже варто сказати (вища — №4).
        val (_, ev2) = run(t0 to snap(soc = 92, current = 10.0, cells = listOf(3400, 3405, 3402, 3510)))
        assertEquals("№4 вища за інші при 92 %. Подивись «Комірки».", ev2.single { it.kind == Alerts.Kind.CELLS }.text)
    }

    // ---------- температура ----------

    @Test
    fun coldAndHotWithHysteresis() {
        val (st, ev) = run(
            t0 to snap(temps = listOf(0.5, 1.0)),
            t0 + 5 * min to snap(temps = listOf(-2.0, 1.0)),
            t0 + 10 * min to snap(temps = listOf(0.4, 1.0)),
            t0 + 15 * min to snap(temps = listOf(-1.0, 1.0)),
        )
        assertEquals(listOf("Холодно: -2,0 °C"), ev.map { it.title })
        assertTrue(st.cold)
        val (st2, ev2) = run(
            t0 + 60 * min to snap(temps = listOf(2.5, 3.0)),
            t0 + 65 * min to snap(temps = listOf(30.0, 51.0)),
            from = st,
        )
        assertFalse(st2.cold)
        assertEquals("Гаряче: 51,0 °C", ev2.single().title)
    }

    // ---------- нема даних ----------

    @Test
    fun noDataAfterAnHourOnceThenCancelledByData() {
        val st0 = Alerts.State(seen = true, lastT = t0)
        assertTrue(Alerts.onNoData(st0, t0, t0 + 59 * min, kyiv).events.isEmpty())
        val s1 = Alerts.onNoData(st0, t0, t0 + 61 * min, kyiv)
        assertEquals("Нема даних з 19:40", s1.events.single().title)
        assertTrue(Alerts.onNoData(s1.state, t0, t0 + 70 * min, kyiv).events.isEmpty())
        assertTrue(Alerts.onNoData(st0, null, t0 + 600 * min, kyiv).events.isEmpty())
        val back = Alerts.onSnapshot(s1.state, snap(), t0 + 80 * min, kyiv)
        val nd = back.events.single { it.kind == Alerts.Kind.NO_DATA }
        assertTrue(nd.cancel)
        assertFalse(back.state.noData)
    }

    // ---------- збереження стану ----------

    @Test
    fun stateRoundTrip() {
        val st = Alerts.State(
            seen = true, lastT = t0, outage = true, outStart = t0 - 5 * min, outSoc = 85, outGap = true, cand = t0, candSoc = 60,
            low = 10, full = true, prot = setOf("CHARGE_COLD", Alerts.CHG_OFF), cells = true, cold = true, hot = false, noData = true,
            candLost = t0 - 60 * min, outLate = true, outLostAt = t0 - 90 * min,
        )
        assertEquals(st, Alerts.State.decode(st.encode()))
        assertEquals(Alerts.State(), Alerts.State.decode(null))
        assertEquals(Alerts.State(), Alerts.State.decode("сміття"))
        assertEquals(emptySet<String>(), Alerts.State.decode(Alerts.State().encode()).prot)
    }

    @Test
    fun everyKindHasUniqueKey() {
        assertEquals(Alerts.Kind.entries.size, Alerts.Kind.entries.map { it.key }.toSet().size)
        assertEquals(Alerts.Kind.TEMP, Alerts.Kind.of("temp"))
    }
}

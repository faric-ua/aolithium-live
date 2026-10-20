package ua.faric.aolithium.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import ua.faric.aolithium.core.DemoFrames
import ua.faric.aolithium.core.tr

/**
 * Довідка: що це, як підключити, чому не видно батарею, що означають числа, звідки знання.
 * Той самий текст — `docs/ua/USER_GUIDE.md` у репо (міняти разом).
 * Альбомна (≥ 600 dp) — дві колонки карток.
 */
class HelpScreen(host: MainActivity, state: Bundle?) : Screen(host) {
    override val view: View
    private val scroll: android.widget.ScrollView

    init {
        val cards = listOf(
            card(LineIcon.BATTERY, tr("Що це", "What is this"), listOf(
                tr(
                    "Aolithium Live — свій застосунок для батареї Aolithium (плата JBD). Показує все, що показував " +
                        "Aolithium 1.3, і більше: кожну комірку, різницю між ними, усі температури, захисти людською мовою.",
                    "Aolithium Live is our own app for the Aolithium battery (JBD BMS). It shows everything Aolithium 1.3 " +
                        "showed, and more: each cell, the difference between cells, all temperatures, protections in plain words.",
                ),
                tr(
                    "Застосунок лише читає батарею — нічого в ній не змінює. Інтернету не потребує.",
                    "The app only reads the battery. It changes nothing in it. It needs no internet.",
                ),
            )),
            card(LineIcon.BLUETOOTH, tr("Як підключити", "How to connect"), listOf(
                tr(
                    "1. Закрий Aolithium на всіх телефонах поруч: плата тримає лише одне підключення.",
                    "1. Close Aolithium on all phones nearby. The BMS allows only one connection.",
                ),
                tr(
                    "2. Тут — «Шукати батареї поруч». Батарея з'явиться вгорі списку (Aolithium називає її на кшталт «AL12-100-4S…»).",
                    "2. Here, tap \"Find batteries nearby\". The battery appears at the top of the list " +
                        "(Aolithium names it like \"AL12-100-4S…\").",
                ),
                tr(
                    "3. Торкнись її — за кілька секунд з'являться числа. Наступного разу вона буде в «Моїх батареях».",
                    "3. Tap it. The numbers appear in a few seconds. Next time it will be in \"My batteries\".",
                ),
                tr(
                    "4. Сховав застосунок — через 5 с він відключається, і батарея знову вільна для інших.",
                    "4. When you hide the app, it disconnects after 5 s. The battery is free for others again.",
                ),
            )),
            card(LineIcon.SEARCH, tr("Не видно батареї?", "Can't see the battery?"), listOf(
                tr(
                    "• Батарея далі ніж 5–10 м або за стіною. Дивись на сигнал біля назви: «слабкий» (−88 dBm і менше) — " +
                        "підійди ближче, на 1–3 м; з таким сигналом підключення зривається.",
                    "• The battery is more than 5–10 m away or behind a wall. Look at the signal next to the name: " +
                        "\"weak\" (−88 dBm or less) means move closer, within 1–3 m. With such a signal the connection drops.",
                ),
                tr(
                    "• Вона зараз підключена до іншого телефона (Aolithium або цей застосунок) — тоді її не видно в пошуку.",
                    "• It is connected to another phone right now (Aolithium or this app). Then search cannot see it.",
                ),
                tr(
                    "• Android 11 і старіші (Huawei Mate 10 Lite, Redmi 9T): потрібні дозвіл на місцезнаходження і ввімкнена " +
                        "геолокація — так Android влаштований для пошуку Bluetooth. Місцезнаходження нікуди не передається.",
                    "• Android 11 and older (Huawei Mate 10 Lite, Redmi 9T): you need the location permission and location " +
                        "turned on. This is how Android works for Bluetooth search. Your location is not sent anywhere.",
                ),
                tr(
                    "• Не допомогло — «Журнал зв'язку» (торкни рядок «Зв'язок» угорі екрана батареї або «Ще») → " +
                        "«Поділитися» → надішли Claude.",
                    "• Still no luck? Open \"Connection log\" (tap the \"Connection\" line at the top of the battery screen, " +
                        "or \"More\") → \"Share\" → send it to Claude.",
                ),
            )),
            card(LineIcon.DOTS, tr("Вкладки внизу", "Tabs at the bottom"), listOf(
                tr(
                    "Зараз — «спідометр» заряду: зони на ободі (червона 0–10 %, помаранчева 10–20 %, зелена 20–90 %, " +
                        "помаранчева 90–100 %), трикутник ззовні — де зараз заряд. Під відсотком — вати або ампери " +
                        "(перемикач «Вати / Ампери»): червоне й стрілка, що тече вгору, — батарея віддає (світла нема); " +
                        "зелене й стрілка вниз — заряджається. Нижче — «вистачить на» (до 20 / 10 / 5 %) або «до повної».",
                    "Now: a charge \"speedometer\". Zones on the rim (red 0–10 %, orange 10–20 %, green 20–90 %, " +
                        "orange 90–100 %); the triangle outside shows the charge now. Below the percent: watts or amps " +
                        "(the \"Watts / Amps\" switch). Red with an arrow flowing up means the battery is discharging " +
                        "(no power); green with an arrow down means charging. Below that: \"lasts for\" (to 20 / 10 / 5 %) " +
                        "or \"until full\".",
                ),
                tr(
                    "Комірки — плитка на кожну комірку: на скільки від середньої і як змінилась за хвилину; як плата їх " +
                        "вирівнює. Графіки — відключення світла, найнижчий заряд по днях (можна позначити генератор), хто з " +
                        "комірок відстає, зарядний, а нижче — історія за 15 хв … 7 днів (пишеться, поки застосунок відкритий, " +
                        "а з «Записом у фоні» — раз на ~5 хв і коли закритий). Плата — здоров'я, лічильники захистів і " +
                        "порада. Ще — запис у фоні, сповіщення, збереження історії в CSV, дії й довідник.",
                    "Cells: a tile for each cell, showing how far it is from average and how it changed in a minute, " +
                        "and how the BMS balances them. Charts: power outages, lowest charge by day (you can mark the " +
                        "generator), the lagging cell, the charger, and below that history for 15 min … 7 days (recorded " +
                        "while the app is open, and with \"Background recording\" every ~5 min even when closed). " +
                        "BMS: health, protection counters and advice. More: background recording, notifications, saving " +
                        "history to CSV, actions and the glossary.",
                ),
                tr(
                    "Сповіщення (у «Ще»): світло зникло / повернулось, мало заряду (20 / 10 / 5 %), батарея повна, захист " +
                        "плати, велика різниця комірок, холод / спека, нема даних понад годину. Кожне — раз, доки стан не " +
                        "мине; кожне вмикається окремо. Коли застосунок закритий, приходять лише з «Записом у фоні» і лише " +
                        "поки телефон поруч із батареєю (Bluetooth — кілька метрів).",
                    "Notifications (in \"More\"): power gone / back, low charge (20 / 10 / 5 %), battery full, BMS " +
                        "protection, large cell difference, cold / heat, no data for over an hour. Each comes once, until " +
                        "the state passes; each one can be turned on separately. When the app is closed, they come only " +
                        "with \"Background recording\" and only while the phone is near the battery (Bluetooth reaches " +
                        "a few meters).",
                ),
                tr(
                    "Віджет на головний екран: затисни порожнє місце на головному екрані → «Віджети» → Aolithium Live → " +
                        "«великий» (4 × 2) або «малий» (2 × 2). Показує заряд, вати зі стрілкою (червона ▲ — віддає, зелена " +
                        "▼ — заряджається), «вистачить на» і коли записано. Оновлюється разом із записом (раз на ~5 хв з " +
                        "«Записом у фоні»). Понад 15 хв без даних — сірий, з часом останнього запису. Торкнись — відкриється батарея.",
                    "Home screen widget: press and hold an empty spot on the home screen → \"Widgets\" → Aolithium Live → " +
                        "the large one (4 × 2) or the small one (2 × 2). It shows charge, watts with an arrow (red ▲ " +
                        "discharging, green ▼ charging), \"lasts for\" and when it was recorded. It updates with each " +
                        "record (every ~5 min with \"Background recording\"). After 15 min without data it turns grey and " +
                        "shows the last record time. Tap it to open the battery.",
                ),
                tr(
                    "Кожен рядок з «ⓘ» — торкнись, і відкриється пояснення: що це, яка норма, коли звернути увагу.",
                    "Tap any line with \"ⓘ\" to open an explanation: what it is, what is normal, when to pay attention.",
                ),
                tr(
                    "Батарея далеко? Її все одно можна відкрити: усі вкладки покажуть останні прочитані дані (з часом) або прочерки.",
                    "Battery far away? You can still open it. All tabs show the last data read (with the time) or dashes.",
                ),
            )),
            card(LineIcon.PULSE, tr("Що означають числа", "What the numbers mean"), listOf(
                tr(
                    "Заряд — скільки відсотків вважає плата. Під ним: залишок з повної ємності в ампер-годинах.",
                    "Charge: the percent the BMS calculates. Below it: remaining of full capacity, in amp-hours.",
                ),
                tr(
                    "Струм: плюс — заряджається, мінус — віддає. «Вистачить на…» — грубо, за нинішнім струмом.",
                    "Current: plus means charging, minus means discharging. \"Lasts for…\" is a rough guess at the current rate.",
                ),
                tr(
                    "Комірки — 4 шт. по ~3,3 В. Різниця до 30 мВ — рівно; 30–100 — помірно; понад 100 — варто стежити " +
                        "(орієнтир, не діагноз).",
                    "Cells: 4 of them, ~3.3 V each. A difference up to 30 mV is even; 30–100 is moderate; over 100 is " +
                        "worth watching (a guide, not a diagnosis).",
                ),
                tr(
                    "Балансування — плата вирівнює комірки наприкінці заряду; це нормально.",
                    "Balancing: the BMS evens out the cells at the end of charge. This is normal.",
                ),
                tr(
                    "Захист спрацював — плата сама вимкнула заряд чи розряд (холод, перегрів, перезаряд…). Застосунок " +
                        "пише, що саме і що робити.",
                    "Protection triggered: the BMS itself turned off charge or discharge (cold, overheating, " +
                        "overcharge…). The app tells you what exactly and what to do.",
                ),
            )),
            card(LineIcon.BATTERY, "Logic Power (JK BMS)", listOf(
                tr(
                    "Батарея з платою JK з'являється в пошуку як «Плата JK BMS». Торкнись — підключиться так само, як Aolithium.",
                    "A battery with a JK BMS appears in search as \"JK BMS\". Tap it. It connects the same way as Aolithium.",
                ),
                tr(
                    "Закрий застосунок JK BMS / Logic Power на інших телефонах: плата тримає лише одне підключення.",
                    "Close the JK BMS / Logic Power app on other phones. The BMS allows only one connection.",
                ),
                tr(
                    "Додатково до Aolithium: балансир JK (з якої комірки в яку переливає і скільки ампер), опір дроту " +
                        "кожної комірки, температура ключів плати, здоров'я й час роботи плати.",
                    "Extra compared to Aolithium: the JK balancer (from which cell to which it moves charge, and how many " +
                        "amps), cell wire resistance for each cell, BMS switch temperature, health and BMS run time.",
                ),
                tr(
                    "Застосунок лише читає — налаштувань плати не змінює. Як виглядає — «Демо без батареї» → «Logic Power».",
                    "The app only reads. It does not change BMS settings. To see how it looks: \"Demo (no battery)\" → \"Logic Power\".",
                ),
            )),
            card(LineIcon.BOOK, tr("Звідки знання", "Where the knowledge comes from"), listOf(
                tr(
                    "Протокол плати JBD відкритий: бібліотеки aiobmsble (Apache-2.0) і esphome-jbd-bms. Код Aolithium не " +
                        "копіювався й не розбирався.",
                    "The JBD BMS protocol is open: the aiobmsble (Apache-2.0) and esphome-jbd-bms libraries. " +
                        "The Aolithium code was not copied or taken apart.",
                ),
                tr(
                    "Logic Power (плата JK BMS, з версії 0.4): протокол теж відкритий — aiobmsble і esphome-jk-bms " +
                        "(Apache-2.0). Пароль плати для читання не потрібен.",
                    "Logic Power (JK BMS, since version 0.4): the protocol is open too: aiobmsble and esphome-jk-bms " +
                        "(Apache-2.0). No BMS password is needed for reading.",
                ),
            )),
        )
        val demo = ui.card()
        demo.addView(ui.label(tr("Демо без батареї", "Demo (no battery)"), LineIcon.INFO))
        demo.addView(ui.muted(tr(
            "Показати екран батареї на готових даних (ті самі байти, що шле плата). Справжня батарея не потрібна.",
            "Show the battery screen with ready-made data (the same bytes the BMS sends). No real battery needed.",
        ), 14f))
        demo.addView(ui.grid(DemoFrames.Scene.entries.map { scene ->
            ui.button(scene.title, Ui.Kind.NORMAL) { host.openBattery("demo:" + scene.name) }
        }, perRow = 1), ui.matchWrap())

        val body: View = if (ui.twoPane) {
            val l = ui.column(14)
            val r = ui.column(14)
            cards.forEachIndexed { i, c -> (if (i % 2 == 0) l else r).addView(c, ui.matchWrap()) }
            r.addView(demo, ui.matchWrap())
            ui.column(14).apply {
                addView(ui.row(16).apply {
                    gravity = Gravity.TOP
                    addView(l, ui.weight(1f))
                    addView(r, ui.weight(1f))
                }, ui.matchWrap())
                addView(version(), ui.matchWrap())
            }
        } else {
            ui.column(14).apply {
                cards.forEach { addView(it, ui.matchWrap()) }
                addView(demo, ui.matchWrap())
                addView(version(), ui.matchWrap())
            }
        }
        scroll = ui.scroll(body)
        view = scroll
        // T26: повернулись з демо — на те саме місце (кнопки демо внизу довідки).
        state?.getInt(KEY_SCROLL, 0)?.takeIf { it > 0 }?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
    }

    override fun save(out: Bundle) {
        out.putInt(KEY_SCROLL, scroll.scrollY)
    }

    private companion object {
        const val KEY_SCROLL = "scrollY"
    }

    private fun card(icon: LineIcon, title: String, lines: List<String>): View = ui.card(8).apply {
        addView(ui.label(title, icon))
        lines.forEach { addView(ui.text(it, 15f, Palette.text)) }
    }

    private fun version(): View = ui.muted("Aolithium Live · " + host.versionLine, 12f).apply { gravity = Gravity.CENTER }
}

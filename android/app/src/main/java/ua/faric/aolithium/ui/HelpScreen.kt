package ua.faric.aolithium.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import ua.faric.aolithium.core.DemoFrames

/**
 * Довідка: що це, як підключити, чому не видно батарею, що означають числа, звідки знання.
 * Той самий текст — `docs/ua/USER_GUIDE.md` у репо (міняти разом).
 * Альбомна (≥ 600 dp) — дві колонки карток.
 */
class HelpScreen(host: MainActivity, @Suppress("UNUSED_PARAMETER") state: Bundle?) : Screen(host) {
    override val view: View

    init {
        val cards = listOf(
            card(LineIcon.BATTERY, "Що це", listOf(
                "Aolithium Live — свій застосунок для батареї Aolithium (плата JBD). Показує все, що показував Aolithium 1.3, і більше: кожну комірку, різницю між ними, усі температури, захисти людською мовою.",
                "Застосунок лише читає батарею — нічого в ній не змінює. Інтернету не потребує.",
            )),
            card(LineIcon.BLUETOOTH, "Як підключити", listOf(
                "1. Закрий Aolithium на всіх телефонах поруч: плата тримає лише одне підключення.",
                "2. Тут — «Шукати батареї поруч». Батарея з'явиться вгорі списку (Aolithium називає її на кшталт «AL12-100-4S…»).",
                "3. Торкнись її — за кілька секунд з'являться числа. Наступного разу вона буде в «Моїх батареях».",
                "4. Сховав застосунок — через 5 с він відключається, і батарея знову вільна для інших.",
            )),
            card(LineIcon.SEARCH, "Не видно батареї?", listOf(
                "• Батарея далі ніж 5–10 м або за стіною. Дивись на сигнал біля назви: «слабкий» (−88 dBm і менше) — підійди ближче, на 1–3 м; з таким сигналом підключення зривається.",
                "• Вона зараз підключена до іншого телефона (Aolithium або цей застосунок) — тоді її не видно в пошуку.",
                "• Android 11 і старіші (Huawei Mate 10 Lite, Redmi 9T): потрібні дозвіл на місцезнаходження і ввімкнена геолокація — так Android влаштований для пошуку Bluetooth. Місцезнаходження нікуди не передається.",
                "• Не допомогло — «Журнал зв'язку» на екрані батареї → «Поділитися» → надішли Claude.",
            )),
            card(LineIcon.DOTS, "Вкладки внизу", listOf(
                "Зараз — «спідометр» заряду: зони на ободі (червона 0–10 %, помаранчева 10–20 %, зелена 20–90 %, помаранчева 90–100 %), трикутник ззовні — де зараз заряд; струм ▲ зелений — заряджається, ▼ червоний — віддає.",
                "Комірки — кожна комірка й температури. Графіки — історія за 15 хв … 7 днів (пишеться, поки застосунок відкритий). Плата — здоров'я, лічильники захистів і порада. Ще — дії й довідник.",
                "Кожен рядок з «ⓘ» — торкнись, і відкриється пояснення: що це, яка норма, коли звернути увагу.",
                "Батарея далеко? Її все одно можна відкрити: усі вкладки покажуть останні прочитані дані (з часом) або прочерки.",
            )),
            card(LineIcon.PULSE, "Що означають числа", listOf(
                "Заряд — скільки відсотків вважає плата. Під ним: залишок з повної ємності в ампер-годинах.",
                "Струм: плюс — заряджається, мінус — віддає. «Вистачить на…» — грубо, за нинішнім струмом.",
                "Комірки — 4 шт. по ~3,3 В. Різниця до 30 мВ — рівно; 30–100 — помірно; понад 100 — варто стежити (орієнтир, не діагноз).",
                "Балансування — плата вирівнює комірки наприкінці заряду; це нормально.",
                "Захист спрацював — плата сама вимкнула заряд чи розряд (холод, перегрів, перезаряд…). Застосунок пише, що саме і що робити.",
            )),
            card(LineIcon.BOOK, "Звідки знання", listOf(
                "Протокол плати JBD відкритий: бібліотеки aiobmsble (Apache-2.0) і esphome-jbd-bms. Код Aolithium не копіювався й не розбирався.",
                "Logic Power (плата JK BMS) — буде у версії 0.4.",
            )),
        )
        val demo = ui.card()
        demo.addView(ui.label("Демо без батареї", LineIcon.INFO))
        demo.addView(ui.muted("Показати екран батареї на готових даних (ті самі байти, що шле плата). Справжня батарея не потрібна.", 14f))
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
        view = ui.scroll(body)
    }

    private fun card(icon: LineIcon, title: String, lines: List<String>): View = ui.card(8).apply {
        addView(ui.label(title, icon))
        lines.forEach { addView(ui.text(it, 15f, Palette.text)) }
    }

    private fun version(): View = ui.muted("Aolithium Live · " + host.versionLine, 12f).apply { gravity = Gravity.CENTER }
}

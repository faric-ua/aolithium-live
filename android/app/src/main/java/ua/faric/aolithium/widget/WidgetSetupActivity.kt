package ua.faric.aolithium.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import ua.faric.aolithium.core.WidgetPick
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.WidgetChoice
import ua.faric.aolithium.ui.Palette
import ua.faric.aolithium.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T22 (v0.4.16): вікно «Яку батарею показувати?» — Android відкриває його, коли віджет додають на головний екран
 * (`android:configure` у `res/xml/widget_*.xml`), а на Android 12+ ще й за «затисни віджет → налаштування»
 * (`widgetFeatures="reconfigurable"`).
 *
 * Батарея одна (або ще жодної) — не питаємо: віджет «автоматично» і вікно одразу закривається. Торкнувся варіанта —
 * вибір запам'ятовується, віджет перемальовується, вікно закривається. «Скасувати» / «Назад» під час додавання —
 * Android не ставить віджет (так влаштовано вибір віджетів), під час зміни — лишається як було.
 */
class WidgetSetupActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val mgr = AppWidgetManager.getInstance(this)
        // Лише наш віджет: вікно відкрите для Android (exported), чужий номер — нічого не робимо.
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || mgr == null || Widgets.ownProvider(mgr, this, widgetId) == null) {
            finish()
            return
        }
        val all = Batteries(this).all()
        val choice = WidgetChoice(this)
        if (!WidgetPick.needsAsk(all.size)) {
            if (choice.get(widgetId) == null) choice.set(widgetId, WidgetPick.AUTO)
            done()
            return
        }
        build(all, choice.get(widgetId))
    }

    private fun build(all: List<Batteries.Saved>, current: String?) {
        val ui = Ui(this)
        val col = ui.column(12)
        col.addView(ui.title(tr("Яку батарею показувати?", "Which battery to show?")), ui.matchWrap())
        col.addView(
            ui.muted(
                tr(
                    "Віджет показуватиме заряд вибраної батареї. Кожен віджет — свою: можна поставити два поруч.",
                    "The widget will show the chosen battery. Each widget has its own: you can put two side by side.",
                ),
                14f,
            ),
            ui.matchWrap(),
        )
        val day = SimpleDateFormat("dd.MM, HH:mm", Locale.ROOT)
        for (b in all) {
            val seen = if (b.lastSeen > 0) tr("дані від ${day.format(Date(b.lastSeen))}", "data from ${day.format(Date(b.lastSeen))}") else ""
            val label = b.title + "\n" + listOf(b.kind.title, seen).filter { it.isNotBlank() }.joinToString(" · ")
            col.addView(ui.choice(label, selected = current.equals(b.address, ignoreCase = true)) { pick(b.address) }, ui.matchWrap())
        }
        col.addView(
            ui.choice(
                tr("Автоматично\nта, що з «Записом у фоні», інакше — остання з зв'язком", "Automatic\nthe one with background recording, otherwise the last connected"),
                selected = current == WidgetPick.AUTO,
            ) { pick(WidgetPick.AUTO) },
            ui.matchWrap(),
        )
        col.addView(
            ui.muted(
                if (Build.VERSION.SDK_INT >= 31) {
                    tr("Змінити потім: затисни віджет → налаштування віджета.", "Change later: long-press the widget → widget settings.")
                } else {
                    tr("Змінити потім: прибери віджет і додай знову.", "Change later: remove the widget and add it again.")
                },
                13f,
            ),
            ui.matchWrap(),
        )
        col.addView(ui.button(tr("Скасувати", "Cancel"), Ui.Kind.QUIET) { finish() }, ui.matchWrap())

        val root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        val scroll = ui.scroll(col, padDp = 20)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 15+ (ціль 36) малює під системними смужками — відступи робимо самі (як у MainActivity).
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setPadding(b.left + ui.dp(20), b.top + ui.dp(20), b.right + ui.dp(20), b.bottom + ui.dp(20))
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
    }

    private fun pick(address: String) {
        WidgetChoice(this).set(widgetId, address)
        done()
    }

    private fun done() {
        Widgets.updateOne(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

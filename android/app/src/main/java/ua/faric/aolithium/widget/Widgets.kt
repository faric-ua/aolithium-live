package ua.faric.aolithium.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import ua.faric.aolithium.R
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.WidgetModel
import ua.faric.aolithium.core.WidgetPick
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.BgSettings
import ua.faric.aolithium.data.LastSnapshot
import ua.faric.aolithium.data.WidgetChoice
import ua.faric.aolithium.ui.MainActivity

/**
 * Віджети на головний екран (v0.3.1): великий 4 × 2 і малий 2 × 2. Показують останній збережений знімок батареї.
 * Яку батарею — кожен віджет свою (T22, v0.4.16): вибір під час додавання (`WidgetSetupActivity`, коли батарей дві й
 * більше), правило — `core/WidgetPick`. «Автоматично» (і всі віджети з давніших версій) — та, для якої увімкнено
 * «Запис у фоні», а якщо такої нема — остання, з якою був зв'язок.
 * Оновлюються з фонового запису (раз на ~5 хв), з відкритого екрана (не частіше ніж раз на хвилину) і
 * самим Android раз на 30 хв (щоб «нема даних» з'являлось, навіть коли фон вимкнено).
 */
object Widgets {
    /** Відкритий екран дає знімки щосекунди — віджет досить оновлювати раз на хвилину. */
    const val SCREEN_EVERY_MS = 60_000L
    private var lastScreenAt = 0L

    /** Нові дані (або «нема зв'язку»). [fromScreen] — від відкритого екрана (рідше). */
    fun onData(ctx: Context, fromScreen: Boolean) {
        val now = System.currentTimeMillis()
        if (fromScreen) {
            if (now - lastScreenAt < SCREEN_EVERY_MS) return
            lastScreenAt = now
        }
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        for (cls in listOf(WidgetLarge::class.java, WidgetSmall::class.java)) {
            val ids = try {
                mgr.getAppWidgetIds(ComponentName(ctx, cls))
            } catch (e: RuntimeException) {
                IntArray(0)
            }
            for (id in ids) update(ctx, mgr, id, large = cls == WidgetLarge::class.java)
        }
    }

    /** Оновити один віджет (після вибору батареї): розмір — за класом, що його показує. */
    fun updateOne(ctx: Context, id: Int) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val cls = ownProvider(mgr, ctx, id) ?: return
        update(ctx, mgr, id, large = cls == WidgetLarge::class.java.name)
    }

    /** Ім'я класу віджета, якщо [id] — наш віджет (чужий номер чи вже прибраний — null). */
    fun ownProvider(mgr: AppWidgetManager, ctx: Context, id: Int): String? = try {
        mgr.getAppWidgetInfo(id)?.provider?.takeIf { it.packageName == ctx.packageName }?.className
    } catch (e: RuntimeException) {
        null
    }

    /** Чию батарею показувати віджету [widgetId]: вибрану, інакше — з увімкненим фоном, інакше — з останнім зв'язком. */
    fun pickBattery(ctx: Context, widgetId: Int): Batteries.Saved? {
        val all = Batteries(ctx).all()
        val bg = BgSettings(ctx)
        val options = all.map { WidgetPick.Option(it.address, it.lastSeen, bg.isOn(it.address)) }
        val address = WidgetPick.choose(WidgetChoice(ctx).get(widgetId), options) ?: return null
        return all.firstOrNull { it.address == address }
    }

    fun model(ctx: Context, widgetId: Int, now: Long = System.currentTimeMillis()): Pair<WidgetModel.Model, String?> {
        val b = pickBattery(ctx, widgetId) ?: return WidgetModel.of("Aolithium Live", null, null, now) to null
        val last = LastSnapshot(ctx).load(b.address)
        return WidgetModel.of(b.title, last?.first, last?.second, now) to b.address
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int, large: Boolean) {
        try {
            val (m, address) = model(ctx, id)
            val opts: Bundle? = mgr.getAppWidgetOptions(id)
            // Книжкова орієнтація: ширина — найменша, висота — найбільша з того, що дав домашній екран.
            val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: if (large) 320 else 160
            val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 160
            val bmp = WidgetArt.draw(m, wDp, hDp, ctx.resources.displayMetrics.density)
            val views = RemoteViews(ctx.packageName, R.layout.widget_frame)
            views.setImageViewBitmap(R.id.widget_image, bmp)
            views.setContentDescription(R.id.widget_image, listOf(m.name, m.percent, m.power, m.status, m.line1).filter { it.isNotBlank() }.joinToString(", "))
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (address != null) open.putExtra(MainActivity.EXTRA_BATTERY, address)
            views.setOnClickPendingIntent(
                R.id.widget_image,
                PendingIntent.getActivity(ctx, 5_000 + id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            mgr.updateAppWidget(id, views)
        } catch (e: RuntimeException) {
            // Віджет — не головне: помилка тут не повинна зупиняти запис у фоні.
            LinkLog.add(tr("віджет: не оновився (${e.javaClass.simpleName})", "widget: not updated (${e.javaClass.simpleName})"))
        }
    }
}

/** Спільне для обох розмірів: Android просить оновити (раз на 30 хв, після додавання) або змінили розмір. */
abstract class BaseWidget(private val large: Boolean) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Widgets.update(context, appWidgetManager, id, large)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        Widgets.update(context, appWidgetManager, appWidgetId, large)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val choice = WidgetChoice(context)
        for (id in appWidgetIds) choice.remove(id)
    }
}

class WidgetLarge : BaseWidget(large = true)

class WidgetSmall : BaseWidget(large = false)

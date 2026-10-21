package ua.faric.aolithium.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import ua.faric.aolithium.R
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.core.AppIcon
import ua.faric.aolithium.core.tr

/**
 * T63: значок застосунку на вибір — читання й перемикання псевдонімів (`activity-alias` у маніфесті) і плитки для «Ще».
 * Правила (що зараз, у якому порядку перемикати) — `core/AppIcon` без Android.
 */
object AppIconUi {
    fun mipmap(v: AppIcon.Variant): Int = when (v) {
        AppIcon.Variant.CLASSIC -> R.mipmap.ic_launcher
        AppIcon.Variant.RING -> R.mipmap.ic_launcher_ring
        AppIcon.Variant.LEVEL -> R.mipmap.ic_launcher_level
        AppIcon.Variant.PULSE -> R.mipmap.ic_launcher_pulse
    }

    private fun component(ctx: Context, v: AppIcon.Variant) = ComponentName(ctx.packageName, v.alias)

    fun current(ctx: Context): AppIcon.Variant {
        val pm = ctx.packageManager
        val states = AppIcon.Variant.entries.associateWith { v ->
            when (try { pm.getComponentEnabledSetting(component(ctx, v)) } catch (e: RuntimeException) { PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> AppIcon.State.ENABLED
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> AppIcon.State.DEFAULT
                else -> AppIcon.State.DISABLED
            }
        }
        return AppIcon.current(states)
    }

    /**
     * Поміняти значок. Спершу вмикаємо новий, потім вимикаємо решту (`AppIcon.plan`) — щоб застосунок не зник зі списку.
     * `DONT_KILL_APP` просить Android не закривати застосунок, але деякі домашні екрани (Samsung) все одно закривають.
     */
    fun apply(ctx: Context, target: AppIcon.Variant): Boolean = try {
        val pm = ctx.packageManager
        for ((v, on) in AppIcon.plan(target)) {
            pm.setComponentEnabledSetting(
                component(ctx, v),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        LinkLog.add(tr("значок застосунку: ${target.mark} — ${target.title}", "app icon: ${target.mark} — ${target.title}"))
        true
    } catch (e: RuntimeException) {
        LinkLog.add(tr("значок застосунку: не вдалося (${e.javaClass.simpleName})", "app icon: failed (${e.javaClass.simpleName})"))
        false
    }

    /** Плитка варіанта: значок і підпис; вибраний — зелена рамка, як у `Ui.choice` (без галочок). */
    fun tile(ui: Ui, v: AppIcon.Variant, selected: Boolean, onClick: () -> Unit): View = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
        minimumHeight = ui.dp(Ui.MIN_TOUCH_DP)
        isClickable = true
        isFocusable = true
        isSelected = selected
        setOnClickListener { onClick() }
        background = ui.pressable(
            if (selected) ui.rounded(Palette.selectedFill, Palette.success, radiusDp = 12, strokeDp = 2)
            else ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12),
            12,
        )
        addView(ImageView(ui.ctx).apply {
            setImageResource(mipmap(v))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        addView(
            ui.text("${v.mark} · ${v.title}", 13.5f, if (selected) Palette.text else Palette.muted, medium = selected).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 2
                setPadding(0, ui.dp(6), 0, 0)
            },
            ui.matchWrap(),
        )
        contentDescription = if (selected) tr("Значок ${v.mark}: ${v.title} · вибрано", "Icon ${v.mark}: ${v.title} · selected")
        else tr("Значок ${v.mark}: ${v.title}", "Icon ${v.mark}: ${v.title}")
    }
}

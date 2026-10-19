package ua.faric.aolithium.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.faric.aolithium.ble.LinkLog

/**
 * Будильник фонового запису, увімкнення телефона й оновлення застосунку → служба `RecordService`.
 *
 * Чому будильник іде сюди, а не одразу в службу: Android тримає телефон не заснути лише поки працює
 * приймач; тут беремо короткий «не спати» (`Wake`) і вже тоді будимо службу.
 * Після оновлення застосунку «поверх» Android зупиняє службу — `MY_PACKAGE_REPLACED` запускає її знову.
 */
class BgReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RecordService.ACTION_TICK -> {
                Wake.hold(context, 60_000L)
                if (RecordService.running) {
                    try {
                        context.startService(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_TICK))
                    } catch (e: IllegalStateException) {
                        RecordService.ensure(context, RecordService.ACTION_TICK)
                    }
                } else {
                    RecordService.ensure(context, RecordService.ACTION_TICK)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (RecordService.ensure(context, RecordService.ACTION_TICK)) LinkLog.add("фон: запущено після ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "увімкнення телефона" else "оновлення"}")
            }
        }
    }
}

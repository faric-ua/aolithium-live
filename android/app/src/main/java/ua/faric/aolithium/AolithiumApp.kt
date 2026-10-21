package ua.faric.aolithium

import android.app.Application
import ua.faric.aolithium.ble.LinkLog
import ua.faric.aolithium.data.LangSettings

/**
 * Старт процесу — до будь-якого екрана, служби «Запис у фоні», будильника чи віджета (T12): завантажити вибрану мову,
 * щоб і сповіщення, і віджет говорили тією ж мовою, що й застосунок.
 */
class AolithiumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LangSettings.load(this)
        LinkLog.init(filesDir) // T57: журнал зв'язку частинами у файлах — переживає перезапуск
    }
}

package ua.faric.aolithium.core

/**
 * T22 (faric 20.10 «підтримую», з огляду схожих застосунків; 21.10 23:08 «1 і 2»): яку батарею показує кожен віджет.
 *
 * До v0.4.16 усі віджети показували одну батарею: з «Записом у фоні», інакше — останню, з якою був зв'язок.
 * Тепер під час додавання віджета застосунок питає, яку батарею показувати (коли їх дві й більше), і кожен віджет
 * пам'ятає свою. «Автоматично» — як було раніше. Вибрану батарею прибрали з «Моїх батарей» — віджет вертається до
 * «автоматично», а не показує порожнечу.
 *
 * Без Android — тест `WidgetPickTest`. Пам'ять вибору — `data/WidgetChoice.kt`, вікно — `widget/WidgetSetupActivity.kt`.
 */
object WidgetPick {
    /** Одна батарея з «Моїх батарей»: адреса, коли востаннє був зв'язок, чи ввімкнено для неї «Запис у фоні». */
    data class Option(val address: String, val lastSeen: Long, val bgOn: Boolean)

    /** Збережений вибір «автоматично» (порожній рядок, щоб відрізнити від «ще не питали» — null). */
    const val AUTO = ""

    /** Чию адресу показувати віджету з вибором [chosen] (null / [AUTO] — автоматично). Нема батарей — null. */
    fun choose(chosen: String?, all: List<Option>): String? {
        if (!chosen.isNullOrBlank()) {
            all.firstOrNull { it.address.equals(chosen, ignoreCase = true) }?.let { return it.address }
        }
        return (all.firstOrNull { it.bgOn } ?: all.maxByOrNull { it.lastSeen })?.address
    }

    /** Питати під час додавання віджета лише тоді, коли є з чого вибирати. */
    fun needsAsk(batteries: Int): Boolean = batteries >= 2
}

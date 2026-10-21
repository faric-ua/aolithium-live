package ua.faric.aolithium.ble

import ua.faric.aolithium.core.Diag

/**
 * T64: останні відповіді плати для «Даних для перевірки» — лише в пам'яті (кілька свіжих кадрів кожного типу,
 * правило — `Diag.keep`). Пишуть `JbdLink` і `JkLink`, читає `guard/DiagReport`. Паролі плати затирає `Diag.redact`
 * уже під час складання файлу.
 */
object DiagFrames {
    private var frames: List<Diag.Raw> = emptyList()

    @Synchronized
    fun add(r: Diag.Raw) {
        frames = Diag.keep(frames, r)
    }

    @Synchronized
    fun all(): List<Diag.Raw> = frames

    /** Найсвіжіший кадр (щоб знати, чи прийшло нове читання після запиту). */
    @Synchronized
    fun lastAt(): Long = frames.maxOfOrNull { it.at } ?: 0L
}

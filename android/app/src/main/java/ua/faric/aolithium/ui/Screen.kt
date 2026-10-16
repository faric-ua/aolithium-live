package ua.faric.aolithium.ui

import android.os.Bundle
import android.view.View

/** Екран. Стан — у Bundle, тож поворот нічого не губить. */
abstract class Screen(val host: MainActivity) {
    val ui: Ui get() = host.ui
    abstract val view: View
    open fun onStart() {}
    open fun onStop() {}
    open fun onResume() {}
    open fun save(out: Bundle) {}

    /** Відновити вікно після повороту. true — ключ цього екрана. */
    open fun restoreDialog(key: String): Boolean = false

    /** Android щойно дав / не дав дозволи. */
    open fun onAccessChanged() {}
}

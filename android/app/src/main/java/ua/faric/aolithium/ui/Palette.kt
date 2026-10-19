package ua.faric.aolithium.ui

/**
 * Кольори Aolithium Live — єдине місце, де вони задаються (перевіряє scripts/audit_ui.py).
 * Основа — як у Track Catcher (темний фон, картки, зелена рамка «вибрано»); свій акцент — блакитний,
 * «електричний», щоб застосунки faric не плутались між собою.
 */
object Palette {
    const val bg = 0xFF0E1116.toInt()
    const val surface = 0xFF171C23.toInt()
    const val surfaceAlt = 0xFF202733.toInt()
    const val border = 0xFF333D4B.toInt()
    const val text = 0xFFF2F5F8.toInt()
    const val muted = 0xFFA3AEBB.toInt()
    const val faint = 0xFF6B7684.toInt()
    const val accent = 0xFF5EC8F2.toInt()
    const val accentFill = 0xFF13283A.toInt()
    const val success = 0xFF62D296.toInt()
    const val selectedFill = 0xFF16271E.toInt()
    const val danger = 0xFFFF7A88.toInt()
    const val dangerFill = 0xFF3A1C21.toInt()
    const val warning = 0xFFF0B968.toInt()
    const val warningFill = 0xFF2B251F.toInt()
    /** Четверта лінія на графіку комірок; «комірка вирівнюється» (не зелене — зелене тут означає заряд). */
    const val violet = 0xFFA99FFF.toInt()
    const val violetFill = 0xFF221F3A.toInt()
    const val track = 0xFF262E3A.toInt()
    const val scrim = 0xCC05070A.toInt()
    const val ripple = 0x33FFFFFF
    const val transparent = 0x00000000
}

package ua.faric.aolithium.core

/**
 * T30 (faric 20.10 03:27): значення в маленькій плитці — завжди **в один рядок**. Скрін A26: «8 відключень» у плитці
 * «за 7 днів» ламалось посеред слова («відключен / ь»), і плитка ставала вищою за сусідні.
 *
 * Правило: спершу трохи зменшити шрифт, щоб усе влізло (щоб число не сховалось за «…»), але не дрібніше за [minPx];
 * якщо й так не влазить — лишається [minPx], а решту обріже «…» (це вже робить сам TextView).
 * Без Android — щоб перевірити тестом.
 */
object TextFit {
    /** Запас 2 %: округлення ширини в пікселях не має повертати перенос. */
    const val MARGIN = 0.98f

    /**
     * Розмір шрифту, з яким текст шириною [needPx] (при шрифті [currentPx]) влізе в [availPx].
     * Влазить — [currentPx] без змін. Ширини ще нема (0) — теж без змін.
     */
    fun size(currentPx: Float, needPx: Float, availPx: Float, minPx: Float): Float {
        if (availPx <= 0f || needPx <= 0f || needPx <= availPx) return currentPx
        val fitted = currentPx * availPx / needPx * MARGIN
        return minOf(currentPx, maxOf(minPx, fitted))
    }

    /**
     * Спільний розмір для ряду плиток: беремо найменший, щоб усі плитки ряду мали однаковий шрифт
     * (інакше одна плитка дрібна, сусідня велика — виглядає як помилка). 0 = ще не рахували.
     */
    fun shared(previousPx: Float, newPx: Float): Float = if (previousPx > 0f) minOf(previousPx, newPx) else newPx
}

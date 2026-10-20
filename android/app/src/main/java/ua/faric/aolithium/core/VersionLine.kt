package ua.faric.aolithium.core

/**
 * T51 (скрін faric 21.10 00:05, A26, 0.4.10): у картці «Оновлення застосунку» рядок «Встановлено 0.4.10 · збірка / 26» —
 * номер збірки переносився на другий рядок. З 0.4.10 версія стала на одну цифру довшою, а колонка значення в рядку
 * «назва — значення» має сталу частку ширини, тож «26» не влазило.
 *
 * Правило: версія — **одним шматком**, ніде не розривається (нерозривні пробіли). Значення бере стільки місця, скільки
 * треба, назва — решту. Якщо разом не влазять (вузький екран, великий шрифт) — назва стає **над** числом, а не ламає його.
 * Без Android — щоб перевірити тестом.
 */
object VersionLine {
    private const val NBSP = ' '

    /** «0.4.10 · збірка 26» без жодного місця для переносу. */
    fun text(versionName: String, versionCode: Long, buildWord: String): String =
        "$versionName · $buildWord $versionCode".replace(' ', NBSP)

    /**
     * Чи ставити назву над значенням: назва ([labelPx]) + проміжок ([gapPx]) + значення ([valuePx]) ширші за рядок ([rowPx]).
     * Ширини ще нема (0) — ні, лишаємо в рядок.
     */
    fun stacked(labelPx: Float, valuePx: Float, rowPx: Float, gapPx: Float): Boolean =
        rowPx > 0f && labelPx + gapPx + valuePx > rowPx * TextFit.MARGIN
}

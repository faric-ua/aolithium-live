package ua.faric.aolithium.core

/**
 * T12 (faric 19.10 01:51, 20.10 05:22): мова інтерфейсу — українська або англійська.
 *
 * Як влаштовано: кожен текст для людини стоїть у коді **парою** — `tr("Українською", "In English")`. Так обидві мови
 * завжди поруч, переклад не загубиться, а тест `test_every_ukrainian_text_has_english` (tests/) шукає українські рядки
 * поза `tr(...)`. Без Android — працює і в тестах ядра, і в службі, і у віджеті.
 *
 * Мову завантажує `AolithiumApp` при старті процесу (`data/LangSettings`); перемикач — «Ще» і головна.
 *
 * Важливо: `tr(...)` рахується **в момент виклику**. Тож не можна класти його туди, де значення рахується один раз
 * (аргументи enum, `val` в object/companion) — там лише `get() = tr(...)`, інакше після перемикання лишиться стара мова.
 */
object Lang {
    enum class Code(val tag: String) { UK("uk"), EN("en") }

    @Volatile
    var current: Code = Code.UK

    val isEn: Boolean get() = current == Code.EN

    fun of(tag: String?): Code = Code.entries.firstOrNull { it.tag == tag } ?: Code.UK
}

/** Текст для людини: українською або англійською — залежно від вибраної мови. */
fun tr(uk: String, en: String): String = if (Lang.current == Lang.Code.EN) en else uk

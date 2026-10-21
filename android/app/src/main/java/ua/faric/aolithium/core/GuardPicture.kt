package ua.faric.aolithium.core

/**
 * T59 (faric 21.10 18:06–18:10): бот сторожа шле не лише текст, а **картинку батареї**, як у застосунку.
 *  - три вигляди — «як спідометр "Зараз"», «як віджет», «віджет + комірки»; плюс «без картинки» (лише текст);
 *  - **кожен учасник вибирає свій** у /settings;
 *  - картинка — до всіх повідомлень (тривоги, «я працюю», /status), підпис — той самий текст;
 *  - «Стан усіх батарей» (/menu) — одна спільна картинка-сітка, коли сторож стежить за кількома батареями.
 *
 * Тут — без Android: вигляди, черга повідомлень з картинками, підпис під картинкою. Малює — `guard/GuardArt.kt`.
 */
object GuardPicture {
    enum class Look(val key: String) {
        GAUGE("gauge"),
        WIDGET("widget"),
        CELLS("cells"),
        NONE("none"),
        ;

        val title: String get() = when (this) {
            // T61 (скрін faric 21.10 19:31): коротко — «Віджет + комірки» Telegram обрізав до «Віджет + комірк…».
            GAUGE -> tr("Спідометр", "Gauge")
            WIDGET -> tr("Віджет", "Widget")
            CELLS -> tr("З комірками", "With cells")
            NONE -> tr("Без картинки", "No picture")
        }

        companion object {
            fun of(key: String?): Look? = entries.firstOrNull { it.key == key }
        }
    }

    /** faric: «плитку схожою на головну сторінку» — тож спершу спідометр, як на вкладці «Зараз». */
    val DEFAULT = Look.GAUGE

    /** Ліміт Telegram на підпис під картинкою — 1024 символи; беремо із запасом. */
    const val CAPTION_MAX = 1000

    /**
     * Як надіслати текст із картинкою: влазить у підпис — одним повідомленням; ні — картинка з першим рядком
     * (заголовком), а повний текст — окремим повідомленням слідом.
     */
    data class Split(val caption: String, val followUp: String?)

    fun split(text: String): Split =
        if (text.length <= CAPTION_MAX) Split(text, null)
        else Split(text.substringBefore('\n').take(CAPTION_MAX), text)

    /**
     * Одне повідомлення в черзі сторожа: кому, текст і (необов'язково) ім'я файлу картинки в пам'яті телефона.
     * Нема інтернету — лежить у черзі; картинки не стало (почистили) — піде лише текст.
     */
    data class Out(val chatId: Long, val text: String, val photo: String? = null)

    /** Рядок черги: «chatId<TAB>текст[<TAB>картинка]». Переноси в тексті — U+2028. Старі рядки (без картинки) читаються. */
    fun encode(items: List<Out>): String = items.joinToString("\n") { o ->
        val t = o.text.replace('\t', ' ').replace('\n', ' ')
        if (o.photo.isNullOrEmpty()) "${o.chatId}\t$t" else "${o.chatId}\t$t\t${o.photo}"
    }

    fun decode(raw: String?): List<Out> = raw.orEmpty().lines().mapNotNull { l ->
        val f = l.split('\t')
        val id = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Out(id, f.getOrElse(1) { "" }.replace(' ', '\n'), f.getOrNull(2)?.takeIf { safeName(it) })
    }

    /** Ім'я файлу картинки — лише наше: «g-<число>-<вигляд>.png», без шляхів. */
    fun fileName(stamp: Long, seq: Int, look: Look): String = "g-$stamp-$seq-${look.key}.png"

    fun safeName(name: String): Boolean = Regex("""g-\d+-\d+-[a-z]+\.png""").matches(name)

    /**
     * Кнопки вибору вигляду в /settings: [CHOSEN] — вибрано, [NOT_CHOSEN] — ні (не галочки — правило інтерфейсу). Два в ряд.
     * T61: було 🔘 / ⚪ — на телефоні сірий і білий кружечки майже однакові; синій видно одразу.
     */
    fun keyboard(current: Look): List<List<Telegram.Button>> =
        Look.entries.map { l -> Telegram.Button((if (l == current) "$CHOSEN " else "$NOT_CHOSEN ") + l.title, "l:${l.key}") }.chunked(2)

    const val CHOSEN = "🔵"
    const val NOT_CHOSEN = "⚪"
}

package ua.faric.aolithium.core

/**
 * T63 (faric 22.10 00:11: «усі значки зберегти і дати можливість вибору»; 00:12 «давай зараз»): значок застосунку на вибір.
 *
 * Як це працює в Android: у маніфесті чотири «псевдоніми» (`activity-alias`) головного вікна, кожен зі своїм значком і
 * з входом у список застосунків. Увімкнений лише один — його значок і видно. Вибір = увімкнути новий, потім вимкнути
 * решту (саме в такому порядку: щоб жодної миті застосунок не зникав зі списку зовсім).
 *
 * Тут — без Android: варіанти, який зараз увімкнений, порядок кроків (тест `AppIconTest`). Перемикає — `ui/AppIconUi.kt`.
 * Варіанти (картинки в чаті 21.10 23:20): А — як було (батарея з блискавкою), Б — кільце заряду, В — рівень заряду,
 * Г — батарея з пульсом.
 */
object AppIcon {
    enum class Variant(val key: String, val alias: String) {
        CLASSIC("classic", "ua.faric.aolithium.IconClassic"),
        RING("ring", "ua.faric.aolithium.IconRing"),
        LEVEL("level", "ua.faric.aolithium.IconLevel"),
        PULSE("pulse", "ua.faric.aolithium.IconPulse"),
        ;

        /** Буква варіанта мовою інтерфейсу: А Б В Г / A B C D. */
        val mark: String get() = tr("АБВГ"[ordinal].toString(), "ABCD"[ordinal].toString())

        val title: String get() = when (this) {
            CLASSIC -> tr("Батарея з блискавкою", "Battery with a bolt")
            RING -> tr("Кільце заряду", "Charge ring")
            LEVEL -> tr("Рівень заряду", "Charge level")
            PULSE -> tr("Батарея з пульсом", "Battery with a pulse")
        }

        companion object {
            fun of(key: String?): Variant? = entries.firstOrNull { it.key == key }
        }
    }

    /** Увімкнений у маніфесті з коробки (`android:enabled="true"`). */
    val DEFAULT = Variant.CLASSIC

    /** Стан псевдоніма, як його каже Android: увімкнено / вимкнено / як у маніфесті. */
    enum class State { ENABLED, DISABLED, DEFAULT }

    /**
     * Який значок зараз. «Як у маніфесті» = увімкнений лише [DEFAULT]. Якщо увімкнених кілька (обірвалося посеред
     * перемикання) — перший не-типовий: його вибрали останнім.
     */
    fun current(states: Map<Variant, State>): Variant {
        fun on(v: Variant) = when (states[v] ?: State.DEFAULT) {
            State.ENABLED -> true
            State.DISABLED -> false
            State.DEFAULT -> v == DEFAULT
        }
        return Variant.entries.firstOrNull { it != DEFAULT && on(it) } ?: DEFAULT
    }

    /** Кроки перемикання на [target]: спершу ввімкнути його, потім вимкнути всі інші. */
    fun plan(target: Variant): List<Pair<Variant, Boolean>> =
        listOf(target to true) + Variant.entries.filter { it != target }.map { it to false }
}

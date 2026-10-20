package ua.faric.aolithium.core

/**
 * Зони заряду на «спідометрі» (faric 18.10 20:47: «поділиш на сектора… куди не можна заходити стрілці,
 * щоб довше батарея прожила»).
 *
 * Для літій-залізо-фосфату (LiFePO4, як в Aolithium) найбільше шкодить глибокий розряд;
 * постійно тримати 100 % — теж трохи старить, але час від часу заряджати до повної треба:
 * тоді плата вирівнює комірки й уточнює відсоток. Тому верхня зона — помаранчева, не червона.
 */
object Zones {
    enum class Tone { RED, ORANGE, GREEN }

    /** T12: назва й порада — функції, бо [ALL] рахується один раз, а мова може змінитись. */
    class Zone(val from: Int, val to: Int, val tone: Tone, private val titleOf: () -> String, private val adviceOf: () -> String) {
        val title: String get() = titleOf()
        val advice: String get() = adviceOf()
    }

    val ALL = listOf(
        Zone(0, 10, Tone.RED, { tr("Глибокий розряд", "Deep discharge") }, {
            tr("Найшкідливіше для батареї. Не доводь сюди: заряди якнайшвидше. Нижче ~10 % плата скоро сама вимкне розряд.",
                "The most harmful for the battery. Don't go here: charge as soon as you can. " +
                    "Below ~10% the BMS will soon turn off discharge by itself.")
        }),
        Zone(10, 20, Tone.ORANGE, { tr("Мало", "Low") }, {
            tr("Ще можна, але краще вже заряджати. Часто сидіти тут — батарея старіє швидше.",
                "Still OK, but better start charging. Staying here often makes the battery age faster.")
        }),
        Zone(20, 90, Tone.GREEN, { tr("Робоча зона", "Working zone") }, {
            tr("Тут батарея живе найдовше. Ідеально — працювати між 20 і 90 %.",
                "Here the battery lives longest. Ideally, work between 20 and 90%.")
        }),
        Zone(90, 101, Tone.ORANGE, { tr("Повна", "Full") }, {
            tr("Нормально, але не тримай на 100 % тижнями. Раз на 1–2 тижні дозаряджай до повної — " +
                "плата вирівняє комірки й уточнить відсоток.",
                "Normal, but don't keep it at 100% for weeks. Once every 1–2 weeks charge it fully — " +
                    "the BMS will even out the cells and correct the percent.")
        }),
    )

    fun of(soc: Int): Zone = ALL.first { soc.coerceIn(0, 100) in it.from until it.to }
}

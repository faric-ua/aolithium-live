package ua.faric.aolithium.core

/**
 * T50 (faric 20.10 23:04 думка; 22.10 01:02 «в принципі можна», 01:06 «давай»): **«Подзвонити» при серйозній тривозі**.
 *
 * Чесно про межі: Android не дає застосунку самому подзвонити — лише відкрити набір номера, натискає людина (так і
 * треба: хибна тривога від застосунку теж небезпечна). Telegram у кнопках дозволяє лише посилання, не дзвінок — тому
 * в боті номери йдуть текстом (повний номер +380… Telegram сам робить натискним).
 *
 * Серйозне — те, що може означати перегрів чи коротке замикання: захисти від перегріву, струму, КЗ, «гаряче», стрибок
 * температури. Холод, перерозряд, різниця комірок — не привід дзвонити 101, тож кнопок там нема.
 *
 * Без Android — тест `EmergencyTest`. Номери — `data/AlertSettings`, кнопки шторки — `bg/Notifier`, бот — `guard/GuardRunner`.
 */
object Emergency {
    /** Рятувальники (ДСНС) в Україні. */
    const val RESCUE = "101"

    data class Contact(val name: String, val number: String)

    /** Що ввімкнув власник: додавати 101 і (необов'язково) свій номер — сусіда, родича. */
    data class Plan(val rescue: Boolean = true, val contact: Contact? = null) {
        val contacts: List<Contact>
            get() = listOfNotNull(
                if (rescue) Contact(tr("Рятувальники", "Rescue service"), RESCUE) else null,
                contact?.takeIf { valid(it.number) },
            )
    }

    /** Захисти, що можуть означати перегрів або КЗ — з ними кнопки дзвінка. */
    val URGENT_PROTECTIONS: Set<Protection> = setOf(
        Protection.CHARGE_HOT, Protection.DISCHARGE_HOT, Protection.CHARGE_OVERCURRENT, Protection.DISCHARGE_OVERCURRENT,
        Protection.SHORT_CIRCUIT, Protection.MOS_HOT, Protection.CHARGE_SHORT, Protection.BATTERY_HOT, Protection.SC_RELEASE_FAILED,
    )

    /** Лишити в номері цифри й «+» на початку: «+38 (050) 123-45-67» → «+380501234567». */
    fun clean(raw: String): String {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        return if (t.startsWith("+")) "+$digits" else digits
    }

    /** Номер схожий на справжній: 3–15 цифр («101» — теж). */
    fun valid(number: String): Boolean = clean(number).count { it.isDigit() } in 3..15

    /** Рядки для Telegram під серйозною тривогою; нема номерів — лише порада. */
    fun telegramText(plan: Plan): String = buildString {
        append(tr("☎️ Якщо бачиш дим, вогонь чи чуєш запах гару — не підходь до батареї, дзвони ", "☎️ If you see smoke or fire or smell burning — keep away from the battery and call "))
        append(if (plan.rescue) "$RESCUE." else tr("рятувальникам.", "the rescue service."))
        plan.contact?.takeIf { valid(it.number) }?.let { c ->
            append('\n').append("☎️ ").append(c.name.ifBlank { tr("Хто поруч", "Someone nearby") }).append(": ").append(clean(c.number))
        }
    }

    /** Підпис кнопки в шторці: «Подзвонити 101» / «Подзвонити Іван». */
    fun buttonLabel(c: Contact): String =
        if (c.number == RESCUE) tr("Подзвонити $RESCUE", "Call $RESCUE") else tr("Подзвонити ", "Call ") + c.name.ifBlank { clean(c.number) }

    /** Адреса для набору номера (ACTION_DIAL) — сам Android не дзвонить. */
    fun dialUri(c: Contact): String = "tel:" + clean(c.number)

    /** Як зберегти свій номер у пам'яті телефона (одним рядком) і прочитати назад. */
    fun encodeContact(c: Contact?): String = if (c == null) "" else c.name.replace('\t', ' ').replace('\n', ' ') + "\t" + clean(c.number)

    fun decodeContact(raw: String?): Contact? {
        val f = raw.orEmpty().split('\t')
        val number = f.getOrNull(1).orEmpty()
        return if (valid(number)) Contact(f[0], number) else null
    }
}

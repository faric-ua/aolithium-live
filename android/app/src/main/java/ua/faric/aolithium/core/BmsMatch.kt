package ua.faric.aolithium.core

/**
 * Хто це в списку Bluetooth: плата JBD (Aolithium і багато інших), JK BMS чи щось інше.
 * Ознаки — з відкритої бібліотеки aiobmsble (`jbd_bms.py`, `jikong_bms.py`).
 */
object BmsMatch {
    enum class Kind(val title: String) {
        JBD("Плата JBD (як в Aolithium)"),
        JK("Плата JK BMS"),
        OTHER("Інший пристрій"),
    }

    /** Початки адрес Bluetooth, які видають модулі JBD. A4:C1:37 — саме в Aolithium. */
    val JBD_OUI = listOf("10:A5:62", "A2:C2:37", "A4:C1:37", "A4:C1:38", "A5:C2:37", "A5:C2:39", "A5:C2:3A", "AA:C2:37", "70:3E:97")

    /** Назви модулів JBD (`*` — будь-що, `?` — один символ). */
    val JBD_NAMES = listOf(
        "@KsM:*", "JBD-*", "LSG-*", "N-?????BL*", "SJ-???-*", "SX1*", "SX60*", "SBL-*", "OGR-*", "TZ-H*", "12???BL*", "24???BL*",
    )

    /** JK BMS: служба FFE0 і код виробника в рекламі. */
    const val JK_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    val JK_MANUFACTURER_IDS = setOf(0x0B65, 0x4B4A)

    fun kind(
        address: String,
        name: String?,
        serviceUuids: Collection<String> = emptyList(),
        manufacturerIds: Collection<Int> = emptyList(),
    ): Kind {
        val services = serviceUuids.map { it.lowercase() }
        if (JK_SERVICE in services && manufacturerIds.any { it in JK_MANUFACTURER_IDS }) return Kind.JK
        val mac = address.uppercase()
        if (JBD_OUI.any { mac.startsWith(it) }) return Kind.JBD
        if (name != null && JBD_NAMES.any { glob(it, name) }) return Kind.JBD
        if (Jbd.SERVICE_UUID in services) return Kind.JBD
        return Kind.OTHER
    }

    fun glob(pattern: String, s: String): Boolean {
        val rx = buildString {
            for (c in pattern) when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        }
        return Regex(rx).matches(s)
    }

    /**
     * Адреса для показу на екрані й у журналах: перші три пари видно (виробник модуля), решта прихована.
     * Повна адреса лишається лише в пам'яті телефона.
     */
    fun shortAddress(address: String): String {
        val parts = address.split(':')
        return if (parts.size == 6) parts.take(3).joinToString(":") + ":··:··:" + parts[5] else address
    }
}

package ua.faric.aolithium.core

/**
 * Захисти плати. JBD — 16 біт «стану захисту» у відповіді «Основне» ([bit]); JK BMS — до 32 біт помилок у кадрі 0x02
 * ([jkBit], v0.4). Біт = 1 — захист / збій зараз діє. Де значення в обох платах однакове — та сама назва
 * (тоді й сповіщення однакові). −1 — у цієї плати такого біта нема.
 * Значення бітів — JBD: aiobmsble `docs/jbd_bms.md`, esphome-jbd-bms; JK: esphome-jk-bms (`jk_bms_ble/__init__.py`).
 */
enum class Protection(val bit: Int, val jkBit: Int = -1) {
    CELL_OVER(0, jkBit = 4),
    CELL_UNDER(1, jkBit = 11),
    PACK_OVER(2, jkBit = 5),
    PACK_UNDER(3, jkBit = 12),
    CHARGE_HOT(4, jkBit = 8),
    CHARGE_COLD(5, jkBit = 9),
    DISCHARGE_HOT(6, jkBit = 15),
    DISCHARGE_COLD(7, jkBit = 27),
    CHARGE_OVERCURRENT(8, jkBit = 6),
    DISCHARGE_OVERCURRENT(9, jkBit = 13),
    SHORT_CIRCUIT(10, jkBit = 14),
    IC_ERROR(11),
    SOFTWARE_LOCK(12),

    // ---------- лише JK BMS (esphome-jk-bms, таблиця помилок JK02) ----------
    WIRE_RESISTANCE(-1, jkBit = 0),
    MOS_HOT(-1, jkBit = 1),
    CELL_COUNT(-1, jkBit = 2),
    CHARGE_SHORT(-1, jkBit = 7),
    COPROCESSOR(-1, jkBit = 10),
    CHARGE_MOS_FAULT(-1, jkBit = 16),
    DISCHARGE_MOS_FAULT(-1, jkBit = 17),
    DISCHARGE_NOT_ON(-1, jkBit = 20),
    BATTERY_HOT(-1, jkBit = 21),
    SENSOR_FAULT(-1, jkBit = 22),
    PL_MODULE(-1, jkBit = 23),
    SC_RELEASE_FAILED(-1, jkBit = 24),
    DISCHARGE_OVERCURRENT_2(-1, jkBit = 25),
    DISCHARGE_OVERCURRENT_3(-1, jkBit = 26),
    ;

    /** Назва й пояснення — обома мовами (T12): через `get()`, бо мову можуть перемкнути вже після створення enum. */
    val title: String get() = when (this) {
        CELL_OVER -> tr("Перезаряд комірки", "Cell overcharge")
        CELL_UNDER -> tr("Перерозряд комірки", "Cell over-discharge")
        PACK_OVER -> tr("Перезаряд батареї", "Battery overcharge")
        PACK_UNDER -> tr("Перерозряд батареї", "Battery over-discharge")
        CHARGE_HOT -> tr("Гаряче для заряду", "Too hot to charge")
        CHARGE_COLD -> tr("Холодно для заряду", "Too cold to charge")
        DISCHARGE_HOT -> tr("Гаряче для розряду", "Too hot to discharge")
        DISCHARGE_COLD -> tr("Холодно для розряду", "Too cold to discharge")
        CHARGE_OVERCURRENT -> tr("Завеликий струм заряду", "Charge current too high")
        DISCHARGE_OVERCURRENT -> tr("Завеликий струм розряду", "Discharge current too high")
        SHORT_CIRCUIT -> tr("Коротке замикання", "Short circuit")
        IC_ERROR -> tr("Помилка мікросхеми плати", "BMS chip error")
        SOFTWARE_LOCK -> tr("Ключі вимкнено програмно", "Switches turned off by software")
        WIRE_RESISTANCE -> tr("Великий опір дроту комірки", "High cell wire resistance")
        MOS_HOT -> tr("Гарячі ключі плати", "BMS switches are hot")
        CELL_COUNT -> tr("Кількість комірок не збігається", "Cell count mismatch")
        CHARGE_SHORT -> tr("Коротке замикання при заряді", "Short circuit while charging")
        COPROCESSOR -> tr("Збій зв'язку всередині плати", "BMS internal link fault")
        CHARGE_MOS_FAULT -> tr("Несправність ключа заряду", "Charge switch fault")
        DISCHARGE_MOS_FAULT -> tr("Несправність ключа розряду", "Discharge switch fault")
        DISCHARGE_NOT_ON -> tr("Розряд не ввімкнувся", "Discharge did not turn on")
        BATTERY_HOT -> tr("Батарея перегріта", "Battery overheated")
        SENSOR_FAULT -> tr("Несправний датчик температури", "Temperature sensor fault")
        PL_MODULE -> tr("Збій модуля попереднього заряду", "Precharge module fault")
        SC_RELEASE_FAILED -> tr("Не вдалось зняти захист від замикання", "Could not clear short-circuit protection")
        DISCHARGE_OVERCURRENT_2 -> tr("Завеликий струм розряду (рівень 2)", "Discharge current too high (level 2)")
        DISCHARGE_OVERCURRENT_3 -> tr("Завеликий струм розряду (рівень 3)", "Discharge current too high (level 3)")
    }

    val explain: String get() = when (this) {
        CELL_OVER -> tr(
            "Якась комірка зарядилась вище межі — плата вимкнула заряд, доки напруга не спаде.",
            "A cell charged above the limit — the BMS turned off charging until the voltage drops.",
        )
        CELL_UNDER -> tr(
            "Якась комірка розрядилась нижче межі — плата вимкнула розряд. Потрібна зарядка.",
            "A cell discharged below the limit — the BMS turned off discharge. It needs charging.",
        )
        PACK_OVER -> tr(
            "Уся батарея вище межі напруги — заряд вимкнено.",
            "The whole battery is above the voltage limit — charging is off.",
        )
        PACK_UNDER -> tr(
            "Уся батарея нижче межі напруги — розряд вимкнено. Потрібна зарядка.",
            "The whole battery is below the voltage limit — discharge is off. It needs charging.",
        )
        CHARGE_HOT -> tr(
            "Температура зависока, щоб заряджати. Заряд вимкнено, доки не охолоне.",
            "The temperature is too high for charging. Charging is off until it cools down.",
        )
        CHARGE_COLD -> tr(
            "Літій-залізо-фосфат не можна заряджати на морозі. Заряд вимкнено, доки не потеплішає.",
            "LiFePO4 must not be charged in frost. Charging is off until it gets warmer.",
        )
        DISCHARGE_HOT -> tr(
            "Температура зависока — розряд вимкнено, доки не охолоне.",
            "The temperature is too high — discharge is off until it cools down.",
        )
        DISCHARGE_COLD -> tr(
            "Занадто холодно навіть для розряду — розряд вимкнено.",
            "Too cold even for discharge — discharge is off.",
        )
        CHARGE_OVERCURRENT -> tr(
            "Зарядний пристрій дає більше, ніж дозволяє плата. Заряд вимкнено на якийсь час.",
            "The charger gives more than the BMS allows. Charging is off for a while.",
        )
        DISCHARGE_OVERCURRENT -> tr(
            "Навантаження бере більше, ніж дозволяє плата. Розряд вимкнено на якийсь час.",
            "The load takes more than the BMS allows. Discharge is off for a while.",
        )
        SHORT_CIRCUIT -> tr(
            "Плата побачила коротке замикання на виході й вимкнула розряд.",
            "The BMS saw a short circuit at the output and turned off discharge.",
        )
        IC_ERROR -> tr(
            "Плата повідомляє про збій своєї мікросхеми вимірювання.",
            "The BMS reports a fault in its measuring chip.",
        )
        SOFTWARE_LOCK -> tr(
            "Заряд або розряд вимкнено командою із застосунку (не цим — він нічого не вмикає й не вимикає).",
            "Charge or discharge was turned off by an app command (not this app — it never turns anything on or off).",
        )
        WIRE_RESISTANCE -> tr(
            "Плата бачить завеликий опір дроту, яким міряє комірку: ослаб або окиснився контакт. Варто підтягнути / почистити.",
            "The BMS sees too much resistance in a cell sense wire: a loose or oxidized contact. Tighten or clean it.",
        )
        MOS_HOT -> tr(
            "Силові ключі (MOS) плати перегрілись — плата обмежує роботу, доки не охолонуть.",
            "The BMS power switches (MOS) overheated — the BMS limits work until they cool down.",
        )
        CELL_COUNT -> tr(
            "Плата бачить іншу кількість комірок, ніж записано в її налаштуваннях.",
            "The BMS sees a different number of cells than set in its settings.",
        )
        CHARGE_SHORT -> tr(
            "Плата побачила коротке замикання з боку зарядного й вимкнула заряд.",
            "The BMS saw a short circuit on the charger side and turned off charging.",
        )
        COPROCESSOR -> tr(
            "Дві мікросхеми плати не можуть домовитись між собою (помилка допоміжного процесора).",
            "Two BMS chips cannot talk to each other (coprocessor error).",
        )
        CHARGE_MOS_FAULT -> tr(
            "Плата повідомляє, що ключ заряду поводиться ненормально.",
            "The BMS reports that the charge switch behaves abnormally.",
        )
        DISCHARGE_MOS_FAULT -> tr(
            "Плата повідомляє, що ключ розряду поводиться ненормально.",
            "The BMS reports that the discharge switch behaves abnormally.",
        )
        DISCHARGE_NOT_ON -> tr(
            "Плата спробувала ввімкнути розряд, але не вийшло (часто — завелике навантаження при старті).",
            "The BMS tried to turn on discharge but failed (often too big a load at start).",
        )
        BATTERY_HOT -> tr(
            "Датчики на комірках показують зависоку температуру.",
            "Cell sensors show too high a temperature.",
        )
        SENSOR_FAULT -> tr(
            "Один з датчиків температури не відповідає або показує нісенітницю.",
            "A temperature sensor does not respond or shows nonsense.",
        )
        PL_MODULE -> tr(
            "Модуль, що плавно вмикає навантаження (PL / precharge), повідомляє про помилку.",
            "The module that softly turns on the load (PL / precharge) reports an error.",
        )
        SC_RELEASE_FAILED -> tr(
            "Після короткого замикання плата не змогла сама повернути розряд.",
            "After a short circuit, the BMS could not restore discharge by itself.",
        )
        DISCHARGE_OVERCURRENT_2 -> tr(
            "Навантаження бере набагато більше, ніж дозволяє плата. Розряд вимкнено на якийсь час.",
            "The load takes much more than the BMS allows. Discharge is off for a while.",
        )
        DISCHARGE_OVERCURRENT_3 -> tr(
            "Навантаження бере набагато більше, ніж дозволяє плата. Розряд вимкнено на якийсь час.",
            "The load takes much more than the BMS allows. Discharge is off for a while.",
        )
    }

    companion object {
        fun fromMask(mask: Int): List<Protection> = entries.filter { it.bit >= 0 && mask and (1 shl it.bit) != 0 }

        /** Біти, яких ми не знаємо (13–15): показуємо номером, а не ховаємо. */
        fun unknownBits(mask: Int): List<Int> {
            val known = entries.filter { it.bit >= 0 }.fold(0) { acc, p -> acc or (1 shl p.bit) }
            return (0..15).filter { mask and (1 shl it) != 0 && known and (1 shl it) == 0 }
        }

        /**
         * JK: біти не про батарею — 3 (порожній, колись «датчик струму»), 18 і 28 (GPS), 19 («час змінити пароль» —
         * плата нагадує про пароль за замовчуванням) — не показуємо як захист, бо це не стан батареї.
         */
        val JK_IGNORED_BITS = setOf(3, 18, 19, 28)

        fun fromJkMask(mask: Long): List<Protection> = entries.filter { it.jkBit >= 0 && mask and (1L shl it.jkBit) != 0L }

        fun unknownJkBits(mask: Long): List<Int> {
            val known = entries.filter { it.jkBit >= 0 }.map { it.jkBit }.toSet() + JK_IGNORED_BITS
            return (0..31).filter { mask and (1L shl it) != 0L && it !in known }
        }
    }
}

package ua.faric.aolithium.core

/**
 * Довідник: докладно про кожен показник, який бачить faric (18.10 20:47: «докладно про кожний пункт
 * можливих показів, плати — все розписано»). Будь-який рядок у застосунку з «ⓘ» відкриває свій запис.
 *
 * Для батареї Aolithium AL12-100-4S: 4 комірки LiFePO4 послідовно, 12,8 В номінал, 100 А·год на етикетці.
 * Числа «норми» — орієнтири для LiFePO4, не обіцянка й не діагноз.
 *
 * T12: тексти — обома мовами. Список будується заново, коли змінилась мова ([ALL] — getter із кешем),
 * тож після перемикання довідник одразу іншою мовою.
 */
object Glossary {
    enum class Group {
        CHARGE, POWER, CELLS, TEMP, BOARD, PROTECT, COUNTERS, LINK, CHARTS;

        /** T12: назва — getter (не аргумент enum), щоб після перемикання мови була новою мовою. */
        val title: String
            get() = when (this) {
                CHARGE -> tr("Заряд", "Charge")
                POWER -> tr("Напруга, струм, потужність", "Voltage, current, power")
                CELLS -> tr("Комірки", "Cells")
                TEMP -> tr("Температура", "Temperature")
                BOARD -> tr("Плата й ключі", "BMS and switches")
                PROTECT -> tr("Захисти", "Protections")
                COUNTERS -> tr("Лічильники захистів", "Protection counters")
                LINK -> tr("Зв'язок", "Connection")
                CHARTS -> tr("Графіки й історія", "Charts and history")
            }
    }

    data class Entry(
        val id: String,
        val group: Group,
        val title: String,
        /** Як це називав Aolithium 1.3 (якщо було). */
        val aolithium: String?,
        val what: String,
        val normal: String,
        val worry: String,
    )

    /** Список для мови, якою його побудовано (T12: перебудовуємо, коли мова змінилась). */
    @Volatile
    private var cache: Pair<Lang.Code, List<Entry>>? = null

    private fun list(): List<Entry> {
        val lang = Lang.current
        cache?.let { if (it.first == lang) return it.second }
        val built = build()
        cache = lang to built
        return built
    }

    private fun build(): List<Entry> {
        val list = ArrayList<Entry>()
        fun e(id: String, g: Group, title: String, aolithium: String?, what: String, normal: String, worry: String) {
            list += Entry(id, g, title, aolithium, what, normal, worry)
        }

        // ---------- заряд ----------
        e("soc", Group.CHARGE, tr("Заряд, %", "Charge, %"), tr("SOC (стрілка)", "SOC (needle)"),
            tr("Скільки енергії лишилось, у відсотках від повної ємності. Рахує сама плата: міряє струм, що входить і виходить. " +
                "На 100 % і після повного заряду вона уточнює свій підрахунок, тому раз на 1–2 тижні корисно дозарядити до кінця.",
                "How much energy is left, as a percent of full capacity. The BMS counts it itself: it measures the current " +
                    "going in and out. At 100% and after a full charge it corrects itself, so it helps to charge fully " +
                    "once every 1–2 weeks."),
            tr("Працювати між 20 і 90 % — так батарея живе найдовше. Зони видно на ободі «спідометра».",
                "Work between 20 and 90% — this way the battery lives longest. The zones are shown on the rim of the \"speedometer\"."),
            tr("Нижче 10 % — глибокий розряд, шкідливо. " +
                "Якщо відсоток стрибає (90 → 60 за хвилину) — плата збилась; повний заряд це виправить.",
                "Below 10% is deep discharge, which is harmful. If the percent jumps (90 → 60 in a minute), the BMS has lost track; " +
                    "a full charge fixes it."))
        e("zones", Group.CHARGE, tr("Зони на «спідометрі»", "Zones on the \"speedometer\""), null,
            tr("Обід циферблата поділено на кольори: червоний 0–10 % (глибокий розряд), помаранчевий 10–20 % (мало), " +
                "зелений 20–90 % (робоча зона), помаранчевий 90–100 % (повна). Трикутник зовні обода — де зараз заряд.",
                "The dial rim is split into colors: red 0–10% (deep discharge), orange 10–20% (low), " +
                    "green 20–90% (working zone), orange 90–100% (full). The triangle outside the rim shows the charge now."),
            tr("Трикутник у зеленій зоні.", "The triangle is in the green zone."),
            tr("Часто в червоній — батарея старіє швидше; на 100 % тижнями — теж не найкраще.",
                "Often in the red — the battery ages faster. At 100% for weeks is not ideal either."))
        e("remain", Group.CHARGE, tr("Залишок, А·год", "Remaining, Ah"), "Residual Capacity",
            tr("Скільки ампер-годин лишилось у батареї зараз. 1 А·год — це струм 1 ампер протягом години.",
                "How many amp-hours are left in the battery now. 1 Ah is a current of 1 amp for one hour."),
            tr("Залишок ÷ повна ємність = відсоток заряду (86,31 ÷ 92,34 = 93 %).",
                "Remaining ÷ full capacity = charge percent (86.31 ÷ 92.34 = 93%)."),
            tr("Нічого — це просто запас. Щоб оцінити, на скільки вистачить, ділимо залишок на струм розряду.",
                "Nothing — it is just the reserve. To estimate how long it lasts, divide the remaining by the discharge current."))
        e("full", Group.CHARGE, tr("Повна ємність, А·год", "Full capacity, Ah"), "Nominal Capacity",
            tr("Скільки ампер-годин плата вважає «100 %». На етикетці 100 А·год, а плата Aolithium каже 92,34 — " +
                "це або налаштування з заводу, або плата «вивчила» реальну ємність після циклів.",
                "How many amp-hours the BMS counts as \"100%\". The label says 100 Ah, but the Aolithium BMS says 92.34 — " +
                    "either a factory setting, or the BMS \"learned\" the real capacity after cycles."),
            tr("Близько до етикетки. 80 % і більше від етикетки — нормальна батарея.",
                "Close to the label. 80% of the label or more is a normal battery."),
            tr("Помітно менше за етикетку (нижче ~80 %) — батарея постаріла: вистачатиме на менше часу.",
                "Much less than the label (below ~80%) — the battery has aged: it will last a shorter time."))
        e("soh", Group.CHARGE, tr("Здоров'я (від етикетки)", "Health (vs label)"), null,
            tr("Повна ємність, яку знає плата, у відсотках від числа на етикетці (з назви моделі: AL12-100-4S → 100 А·год). " +
                "Це оцінка: плата може просто мати заводське налаштування ємності.",
                "The full capacity known by the BMS, as a percent of the label number (from the model name: AL12-100-4S → 100 Ah). " +
                    "This is an estimate: the BMS may just have a factory capacity setting."),
            tr("90–100 % — як нова; 80–90 % — добре.", "90–100% — like new; 80–90% — good."),
            tr("Нижче 80 % — батарея помітно постаріла.", "Below 80% — the battery has clearly aged."))
        e("time", Group.CHARGE, tr("Вистачить на / до повної", "Lasts for / until full"), null,
            tr("Оцінка: залишок ÷ струм (або скільки бракує до повної ÷ струм заряду). Струм беремо середній за 2 хвилини, " +
                "і число перераховується лише тоді, коли струм стабільно змінився більше ніж на 10 % — тому воно не скаче щосекунди. " +
                "При розряді окремо видно, скільки до 20 % (кінець зеленої зони), 10 % і 5 %. При заряді — скільки до повної " +
                "і скільки відсотків додає кожна година такого заряду (зручно, щоб вирішити, скільки ганяти генератор). " +
                "До 100 % заряд доводить інвертор, а зупиняє сама плата.",
                "An estimate: remaining ÷ current (or what is missing to full ÷ charge current). We use the average current " +
                    "over 2 minutes, and the number changes only when the current steadily changes by more than 10% — " +
                    "so it does not jump every second. When discharging, you also see the time to 20% (end of the green zone), " +
                    "10% and 5%. When charging — the time until full and how many percent each hour of such charging adds " +
                    "(handy to decide how long to run the generator). The inverter brings the charge to 100%, " +
                    "and the BMS itself stops it."),
            tr("Порівнюй з тим, що тобі треба (наприклад, ніч без світла). 0 % — це не смерть батареї: там плата сама вимкне розряд, " +
                "але краще не опускатись нижче 10 %.",
                "Compare with what you need (for example, a night without power). 0% is not the death of the battery: " +
                    "there the BMS turns off discharge itself, but better not go below 10%."),
            tr("Насправді трохи менше: наприкінці розряду напруга падає, а інвертор бере більше струму.",
                "In reality a bit less: at the end of discharge the voltage drops and the inverter draws more current."))
        e("units", Group.POWER, tr("Вати чи ампери на «спідометрі»", "Watts or amps on the \"speedometer\""), null,
            tr("Під відсотком — велике число: потужність у ватах (скільки зараз бере навантаження чи дає зарядний) або струм в амперах. " +
                "Перемикач «Вати / Ампери» під циферблатом; друге число — дрібніше нижче. " +
                "Червоне й стрілка, що «тече» вгору, — батарея віддає (світла нема). Зелене й стрілка вниз — заряджається.",
                "Under the percent is a big number: power in watts (how much the load takes now or the charger gives) " +
                    "or current in amps. The \"Watts / Amps\" switch is under the dial; the second number is smaller, below. " +
                    "Red with an arrow \"flowing\" up — the battery is discharging (no power). Green with an arrow down — charging."),
            tr("Холодильник і роутер разом — зазвичай кілька десятків ват у середньому.",
                "A fridge and a router together — usually a few dozen watts on average."),
            "—")
        e("cycles", Group.CHARGE, tr("Цикли", "Cycles"), "Cycles",
            tr("Скільки повних циклів «заряд–розряд» нарахувала плата (два розряди по 50 % = один цикл).",
                "How many full \"charge–discharge\" cycles the BMS has counted (two 50% discharges = one cycle)."),
            tr("LiFePO4 витримує зазвичай 2000–6000 циклів. 123 цикли — батарея ще молода.",
                "LiFePO4 usually lasts 2000–6000 cycles. 123 cycles — the battery is still young."),
            tr("Нічого — це лічильник. Корисно бачити, як швидко він росте.",
                "Nothing — it is a counter. It is useful to see how fast it grows."))

        // ---------- електрика ----------
        e("voltage", Group.POWER, tr("Напруга батареї, В", "Battery voltage, V"), "Voltage",
            tr("Напруга всієї батареї (4 комірки разом). Для LiFePO4 вона майже не змінюється між 20 і 90 % — " +
                "тому відсоток рахує плата, а не напруга.",
                "Voltage of the whole battery (4 cells together). For LiFePO4 it barely changes between 20 and 90% — " +
                    "so the percent comes from the BMS, not from the voltage."),
            tr("Спокій: ~13,0–13,4 В. Заряд: до 14,2–14,4 В. Розряд під навантаженням: 12,5–13,2 В.",
                "Idle: ~13.0–13.4 V. Charging: up to 14.2–14.4 V. Discharging under load: 12.5–13.2 V."),
            tr("Нижче ~12,0 В — батарея майже порожня. Вище 14,6 В — перезаряд (плата вимкне заряд).",
                "Below ~12.0 V — the battery is almost empty. Above 14.6 V — overcharge (the BMS will turn off charging)."))
        e("current", Group.POWER, tr("Струм, А", "Current, A"), "Current",
            tr("Скільки ампер іде зараз. Плата шле плюс, коли батарея заряджається, і мінус, коли віддає. На екрані: зелене й стрілка ▼ " +
                "(енергія йде в батарею) — заряджається; червоне й стрілка ▲ (енергія йде з батареї) — віддає.",
                "How many amps flow now. The BMS sends plus when the battery is charging and minus when discharging. " +
                    "On screen: green with arrow ▼ (energy goes into the battery) — charging; " +
                    "red with arrow ▲ (energy goes out of the battery) — discharging."),
            tr("Для батареї 100 А·год комфортно до ~50 А тривало; максимум — що дозволяє плата (зазвичай 100 А).",
                "For a 100 Ah battery, up to ~50 A for a long time is comfortable; the maximum is what the BMS allows (usually 100 A)."),
            tr("Великий струм розряду довго — гріє батарею; може спрацювати захист плати «завеликий струм розряду».",
                "High discharge current for a long time heats the battery; the BMS may trip \"discharge overcurrent\"."))
        e("power", Group.POWER, tr("Потужність, Вт", "Power, W"), "Power",
            tr("Напруга × струм: скільки ват іде в батарею (+) чи з неї (−) прямо зараз. 1000 Вт протягом години — 1 кВт·год.",
                "Voltage × current: how many watts go into the battery (+) or out of it (−) right now. 1000 W for one hour is 1 kWh."),
            tr("Порівнюй з потужністю приладів, які живить інвертор.",
                "Compare with the power of the devices the inverter runs."),
            tr("Потужність більша, ніж витримують інвертор чи плата, — краще розділити навантаження.",
                "Power is higher than the inverter or the BMS is rated for — better split the load."))
        e("energy", Group.POWER, tr("Заряджено / віддано, Вт·год", "Charged / discharged, Wh"), null,
            tr("Скільки енергії зайшло в батарею і вийшло з неї за вибраний період. Рахує застосунок із записаних точок " +
                "(лише коли він був на зв'язку з батареєю).",
                "How much energy went into and out of the battery in the selected period. The app counts it from recorded points " +
                    "(only while it was connected to the battery)."),
            tr("Батарея 12,8 В × 100 А·год ≈ 1280 Вт·год повністю.",
                "A 12.8 V × 100 Ah battery holds ≈ 1280 Wh when full."),
            tr("Якщо застосунок був закритий — ця частина часу не врахована (видно як розрив на графіках).",
                "If the app was closed, that time is not counted (shown as a gap on the charts)."))

        // ---------- комірки ----------
        e("cells", Group.CELLS, tr("Комірки", "Cells"), null,
            tr("Батарея — це 4 комірки LiFePO4 послідовно, по ~3,2–3,4 В. Напруга всієї батареї = сума комірок. " +
                "Aolithium 1.3 комірок не показував — їх бачить лише плата.",
                "The battery is 4 LiFePO4 cells in series, ~3.2–3.4 V each. The battery voltage = the sum of the cells. " +
                    "Aolithium 1.3 did not show the cells — only the BMS sees them."),
            tr("Спокій: 3,25–3,35 В кожна. Наприкінці заряду — до 3,45–3,6 В.",
                "Idle: 3.25–3.35 V each. At the end of charge — up to 3.45–3.6 V."),
            tr("Одна комірка помітно вища чи нижча за інші — розбаланс (див. «Різниця»).",
                "One cell is clearly higher or lower than the others — imbalance (see \"Cell difference\")."))
        e("avgcell", Group.CELLS, tr("Середня комірка", "Average cell"), "Average voltage",
            tr("Середня напруга однієї комірки (напруга батареї ÷ 4).", "Average voltage of one cell (battery voltage ÷ 4)."),
            tr("~3,3 В у спокої.", "~3.3 V at idle."),
            tr("Нижче 3,0 В — батарея майже порожня; вище 3,65 В — межа перезаряду.",
                "Below 3.0 V — the battery is almost empty; above 3.65 V — the overcharge limit."))
        e("delta", Group.CELLS, tr("Різниця комірок, мВ", "Cell difference, mV"), null,
            tr("Найвища комірка мінус найнижча. Показує, наскільки рівно заряджені комірки.",
                "Highest cell minus lowest. Shows how evenly the cells are charged."),
            tr("У спокої до 30 мВ — рівно. Наприкінці заряду різниця росте — це нормально, плата її вирівнює.",
                "At idle up to 30 mV — even. At the end of charge the difference grows — this is normal, the BMS evens it out."),
            tr("Понад 100 мВ у спокої — розбаланс: одна комірка першою ловить захист, батарея віддає менше. " +
                "Допомагає повний заряд і час на балансування.",
                "Over 100 mV at idle — imbalance: one cell hits protection first, and the battery gives less. " +
                    "A full charge and time for balancing help."))
        e("cellTrend", Group.CELLS, tr("Комірка: від середньої і за 1 хв", "Cell: from average and per min"), null,
            tr("«Від середньої» — на скільки мілівольт комірка вища (+) чи нижча (−) за середню з усіх. " +
                "«За 1 хв» — як змінилась її напруга за останню хвилину: ▲ зелене — росте (заряджається), ▼ червоне — падає. " +
                "Струм через усі комірки однаковий (вони з'єднані ланцюжком), а от напруга росте по-різному — " +
                "так видно, яка «тікає» вперед наприкінці заряду. Рамка: блакитна — найвища, помаранчева — найнижча.",
                "\"From average\" — how many millivolts the cell is above (+) or below (−) the average of all cells. " +
                    "\"Per 1 min\" — how its voltage changed in the last minute: ▲ green — rising (charging), ▼ red — falling. " +
                    "The current through all cells is the same (they are linked in a chain), but the voltage rises differently — " +
                    "this shows which cell \"runs ahead\" at the end of charge. Frame: light blue — highest, orange — lowest."),
            tr("Від середньої — кілька мВ, за хвилину — кілька мВ в один бік у всіх.",
                "From average — a few mV; per minute — a few mV in the same direction for all cells."),
            tr("Одна комірка за хвилину росте набагато швидше за інші наприкінці заряду — вона «менша», балансування її стримує.",
                "At the end of charge one cell rises much faster per minute than the others — it is \"smaller\", " +
                    "and balancing holds it back."))
        e("balance", Group.CELLS, tr("Балансування", "Balancing"), "Balance State",
            tr("Плата підрозряджає найвищі комірки маленьким струмом (у тепло, через резистор), поки інші їх доганяють — " +
                "це пасивне балансування, як у плати Aolithium. Працює наприкінці заряду. Скільки ампер — плата JBD не повідомляє, " +
                "лише «вирівнює / ні»; на плитці комірки — фіолетова мітка «вирівнюється». " +
                "У Logic Power (плата JK) — активний балансир: перекачує енергію з найвищої комірки в найнижчу і каже струм " +
                "(див. «Активний балансир JK»).",
                "The BMS slowly drains the highest cells with a small current (into heat, through a resistor) while the others " +
                    "catch up — this is passive balancing, as in the Aolithium BMS. It works at the end of charge. " +
                    "The JBD BMS does not report how many amps, only \"balancing / not\"; the cell tile shows a purple " +
                    "\"balancing\" mark. Logic Power (JK BMS) has an active balancer: it moves energy from the highest cell " +
                    "to the lowest and reports the current (see \"JK active balancer\")."),
            tr("«Так» наприкінці заряду — добре. «Ні» в спокої — нормально.",
                "\"Yes\" at the end of charge — good. \"No\" at idle — normal."),
            tr("Балансування весь час і велика різниця — комірки розходяться, варто стежити.",
                "Balancing all the time and a big difference — the cells are drifting apart, keep an eye on it."))

        // ---------- температура ----------
        e("temp", Group.TEMP, tr("Температура", "Temperature"), "Temperature",
            tr("Датчики всередині батареї. У плати Aolithium їх 3 (Aolithium 1.3 показував лише один). Який датчик де — плата не каже: " +
                "зазвичай один на платі біля ключів, інші — на комірках.",
                "Sensors inside the battery. The Aolithium BMS has 3 (Aolithium 1.3 showed only one). The BMS does not say " +
                    "which sensor is where: usually one is on the board near the switches, the others are on the cells."),
            tr("Найкраще 15–35 °C. Заряджати LiFePO4 можна від 0 °C, віддавати — від −20 °C.",
                "Best is 15–35 °C. LiFePO4 can be charged from 0 °C and discharged from −20 °C."),
            tr("Нижче 0 °C плата заборонить заряд (захист «холодно для заряду»). Вище 45–55 °C — перегрів, плата вимкне ключі.",
                "Below 0 °C the BMS blocks charging (the \"too cold to charge\" protection). Above 45–55 °C — overheating, " +
                    "the BMS turns off the switches."))

        // ---------- плата ----------
        e("chargeSw", Group.BOARD, tr("Заряд: дозволено / заборонено", "Charge: allowed / off"), "Charge Switch: open",
            tr("Ключ (транзистор) заряду на платі. «Дозволено» — батарею можна заряджати. Плата вимикає його сама, коли спрацював захист.",
                "The charge switch (transistor) on the BMS. \"Allowed\" — the battery can be charged. " +
                    "The BMS turns it off by itself when a protection trips."),
            tr("«Дозволено».", "\"Allowed\"."),
            tr("«Вимкнено» — дивись «Що спрацювало» на вкладці «Зараз».",
                "\"Off\" — see \"What tripped\" on the \"Now\" tab."))
        e("dischargeSw", Group.BOARD, tr("Розряд: дозволено / заборонено", "Discharge: allowed / off"), "Discharge Switch: open",
            tr("Ключ розряду. «Дозволено» — батарея може живити навантаження.",
                "The discharge switch. \"Allowed\" — the battery can power the load."),
            tr("«Дозволено».", "\"Allowed\"."),
            tr("«Вимкнено» — батарея нічого не віддає: зазвичай перерозряд, перегрів чи завеликий струм.",
                "\"Off\" — the battery gives nothing: usually over-discharge, overheating or overcurrent."))
        e("protState", Group.BOARD, tr("Захист: усе гаразд / спрацював", "Protection: all OK / tripped"), "Protection State: close",
            tr("Чи діє зараз хоч один захист плати. В Aolithium «close» означало «закрито, усе гаразд».",
                "Whether any BMS protection is active now. In Aolithium, \"close\" meant \"closed, all OK\"."),
            tr("«Усе гаразд».", "\"All OK\"."),
            tr("«Спрацював» — застосунок пише, який саме, і що робити.",
                "\"Tripped\" — the app says which one and what to do."))
        e("model", Group.BOARD, tr("Назва плати", "BMS name"), "Battery Model",
            tr("Назва, записана в плату на заводі. Для Aolithium — модель батареї: AL12-100-4S (12 В, 100 А·год, 4 комірки).",
                "The name written into the BMS at the factory. " +
                    "For Aolithium it is the battery model: AL12-100-4S (12 V, 100 Ah, 4 cells)."),
            "—", "—")
        e("firmware", Group.BOARD, tr("Версія програми плати", "BMS firmware version"), null,
            tr("Версія вбудованої програми плати JBD.", "Version of the built-in software of the JBD BMS."), "—", "—")
        e("made", Group.BOARD, tr("Дата виготовлення", "Manufacture date"), null,
            tr("Дата, записана в плату на заводі (може бути датою плати, а не батареї).",
                "The date written into the BMS at the factory (it may be the date of the BMS, not of the battery)."), "—", "—")
        // ---------- JK BMS (v0.4, Logic Power) ----------
        e("jkbalance", Group.CELLS, tr("Активний балансир JK", "JK active balancer"), null,
            tr("Плата JK бере енергію з найвищої комірки й віддає найнижчій (через котушку, майже без нагріву) — і каже, скільки ампер. " +
                "Вмикається сама, коли комірки розійшлись більше за поріг з налаштувань плати " +
                "(у Logic Power — від 3,30 В і різниці 10 мВ).",
                "The JK BMS takes energy from the highest cell and gives it to the lowest (through a coil, with almost no heat) — " +
                    "and reports how many amps. It turns on by itself when the cells drift apart more than the threshold " +
                    "in the BMS settings (on Logic Power — from 3.30 V and a 10 mV difference)."),
            tr("Наприкінці заряду працює — нормально; 0,1–2 А залежно від плати.",
                "Working at the end of charge — normal; 0.1–2 A depending on the BMS."),
            tr("Працює постійно, а різниця не меншає — одна комірка слабша за інші.",
                "Works all the time, but the difference does not shrink — one cell is weaker than the others."))
        e("wire", Group.CELLS, tr("Опір дроту комірки", "Cell wire resistance"), null,
            tr("Плата міряє кожну комірку тонким дротом і рахує його опір у міліомах. Чим менше — тим точніша напруга комірки.",
                "The BMS measures each cell through a thin wire and calculates its resistance in milliohms. " +
                    "The lower it is, the more accurate the cell voltage."),
            tr("Схожі числа в усіх комірок (зазвичай десятки мОм).", "Similar numbers for all cells (usually tens of mΩ)."),
            tr("Одна комірка помітно більша за інші або плата пише «великий опір дроту» — ослаб чи окиснився контакт: підтягни, почисти.",
                "One cell is clearly higher than the others, or the BMS reports \"high wire resistance\" — a loose or oxidized " +
                    "contact: tighten it, clean it."))
        e("mos", Group.TEMP, tr("Ключі плати (MOS)", "BMS switches (MOS)"), null,
            tr("Температура силових ключів — «кранів», якими плата вмикає заряд і розряд. Гріються від великого струму.",
                "Temperature of the power switches — the \"taps\" the BMS uses to turn charge and discharge on and off. " +
                    "They heat up from high current."),
            tr("До 50–60 °C.", "Up to 50–60 °C."),
            tr("Понад 70 °C — плата може обмежити струм (захист «гарячі ключі»).",
                "Over 70 °C — the BMS may limit the current (the \"hot switches\" protection)."))
        e("jksoh", Group.BOARD, tr("Здоров'я (каже плата JK)", "Health (JK BMS value)"), null,
            tr("Плата JK сама рахує здоров'я батареї у відсотках. Як саме — виробник не пояснює; часто довго лишається 100 %.",
                "The JK BMS calculates battery health in percent by itself. The maker does not explain how; " +
                    "it often stays at 100% for a long time."),
            tr("90–100 %.", "90–100%."),
            tr("Помітно впало — порівняй «повну» ємність з етикеткою.",
                "Dropped noticeably — compare the \"full\" capacity with the label."))
        e("cyclecap", Group.BOARD, tr("Пройдено всього", "Total throughput"), null,
            tr("Скільки ампер-годин батарея віддала за все життя плати. Поділи на повну ємність — вийде приблизно кількість повних циклів.",
                "How many amp-hours the battery has given over the whole life of the BMS. Divide by the full capacity " +
                    "to get roughly the number of full cycles."),
            "—", "—")
        e("runtime", Group.BOARD, tr("Час роботи плати", "BMS run time"), null,
            tr("Скільки плата працює від першого увімкнення (за її власним годинником).",
                "How long the BMS has been working since it was first turned on (by its own clock)."),
            "—", "—")
        e("heating", Group.TEMP, tr("Підігрів", "Heater"), null,
            tr("Деякі плати JK уміють гріти батарею перед зарядом на морозі (якщо підключено нагрівач). " +
                "Показуємо, чи гріє зараз і з яким струмом.",
                "Some JK BMS boards can heat the battery before charging in frost (if a heater is connected). " +
                    "We show whether it is heating now and with what current."),
            tr("Влітку — вимкнено.", "Off in summer."),
            tr("Гріє, коли тепло, — перевір налаштування нагріву.", "Heating while it is warm — check the heater settings."))
        e("stage", Group.CHARGE, tr("Етап заряду (JK)", "Charge stage (JK)"), null,
            tr("Нові плати JK кажуть, на якому етапі заряд: основний (великим струмом), дозаряд (напруга тримається, струм спадає), " +
                "підтримка (float — батарея повна, лише підтримує).",
                "New JK BMS boards report the charge stage: bulk (high current), absorption (the voltage is held, " +
                    "the current falls), float (the battery is full and is only kept topped up)."),
            "—", "—")
        e("jkboard", Group.BOARD, tr("Тип плати: JK BMS (Jikong)", "BMS type: JK BMS (Jikong)"), null,
            tr("У батареї Logic Power — плата JK BMS з активним балансиром. Її протокол відкритий (aiobmsble, esphome-jk-bms), " +
                "тому цей застосунок може її читати. Пароль плати для читання не потрібен. " +
                "Застосунок лише читає — нічого в платі не змінює.",
                "The Logic Power battery has a JK BMS with an active balancer. Its protocol is open (aiobmsble, esphome-jk-bms), " +
                    "so this app can read it. No BMS password is needed for reading. " +
                    "The app only reads — it changes nothing in the BMS."),
            "—", "—")

        e("board", Group.BOARD, tr("Тип плати: JBD (Jiabaida)", "BMS type: JBD (Jiabaida)"), null,
            tr("Усередині Aolithium — стандартна плата JBD («Xiaoxiang»). Її протокол відкритий, тому цей застосунок може її читати. " +
                "Застосунок лише читає — нічого в платі не змінює.",
                "Inside Aolithium is a standard JBD BMS (\"Xiaoxiang\"). Its protocol is open, so this app can read it. " +
                    "The app only reads — it changes nothing in the BMS."),
            "—", "—")

        // ---------- лічильники ----------
        e("counters", Group.COUNTERS, tr("Захисти за весь час", "Protections, all time"), null,
            tr("Скільки разів плата вмикала кожен захист від виготовлення (або скидання). Це історія, а не те, що діє зараз.",
                "How many times the BMS has triggered each protection since it was made (or reset). " +
                    "This is history, not what is active now."),
            tr("Нулі або одиниці.", "Zeros or ones."),
            tr("Сотні й тисячі — щось систематично доводить батарею до межі (найчастіше — налаштування зарядного).",
                "Hundreds or thousands — something keeps pushing the battery to the limit (most often the charger settings)."))
        e("cnt_cell_over", Group.COUNTERS, tr("Перезаряд комірки (лічильник)", "Cell overcharge (counter)"), null,
            tr("Скільки разів якась комірка доходила до межі перезаряду (у Aolithium — 3,65 В), і плата зупиняла заряд.",
                "How many times a cell reached the overcharge limit (3.65 V on Aolithium) and the BMS stopped charging."),
            tr("Поодинокі випадки наприкінці заряду — нормально.", "A few cases at the end of charge are normal."),
            tr("Сотні–тисячі (у батареї faric 18.10 — 2016) — зарядний пристрій, мабуть, дає забагато вольт " +
                "(для 4S LiFePO4 радять 14,2–14,4 В " +
                "на заряді, 13,5–13,6 В на підтримці) або одна комірка відстає від інших. Перевір налаштування інвертора.",
                "Hundreds or thousands (faric's battery on Oct 18 — 2016) — the charger probably gives too many volts " +
                    "(for 4S LiFePO4, 14.2–14.4 V for charging and 13.5–13.6 V for float are recommended), " +
                    "or one cell lags behind the others. Check the inverter settings."))
        e("cnt_cell_under", Group.COUNTERS, tr("Перерозряд комірки (лічильник)", "Cell over-discharge (counter)"), null,
            tr("Скільки разів комірка падала нижче межі (у Aolithium — 2,5 В), і плата вимикала розряд.",
                "How many times a cell fell below the limit (2.5 V on Aolithium) and the BMS turned off discharge."),
            "0.",
            tr("Кожен випадок — глибокий розряд. Не доводь батарею до вимкнення.",
                "Each case is a deep discharge. Do not run the battery down until it shuts off."))
        e("cnt_overcurrent", Group.COUNTERS, tr("Завеликий струм (лічильник)", "Overcurrent (counter)"), null,
            tr("Скільки разів струм заряду чи розряду перевищував межу плати.",
                "How many times the charge or discharge current went over the BMS limit."),
            tr("0–кілька (наприклад, пусковий струм потужного приладу).",
                "0 to a few (for example, the start-up current of a powerful device)."),
            tr("Часто — навантаження завелике для батареї; розділи його або обери потужнішу батарею.",
                "Often — the load is too big for the battery; split it or choose a more powerful battery."))
        e("cnt_temp", Group.COUNTERS, tr("Гаряче / холодно (лічильник)", "Hot / cold (counter)"), null,
            tr("Скільки разів плата блокувала заряд чи розряд через температуру.",
                "How many times the BMS blocked charge or discharge because of temperature."),
            "0.",
            tr("«Холодно для заряду» взимку — батарея стоїть у холоді; перенеси в тепле місце.",
                "\"Too cold to charge\" in winter — the battery stands in the cold; move it to a warm place."))
        e("cnt_short", Group.COUNTERS, tr("Коротке замикання (лічильник)", "Short circuit (counter)"), null,
            tr("Скільки разів плата бачила коротке замикання на виході й рятувала батарею.",
                "How many times the BMS saw a short circuit at the output and saved the battery."),
            "0.",
            tr("Будь-яке — перевір дроти й клеми.", "Any — check the wires and terminals."))

        // ---------- зв'язок ----------
        e("signal", Group.LINK, tr("Сигнал, dBm", "Signal, dBm"), null,
            tr("Сила радіосигналу Bluetooth від батареї. Чим ближче до нуля — тим ближче батарея. Сам по собі скаче на ±5–10 щосекунди.",
                "Strength of the Bluetooth radio signal from the battery. The closer to zero, the closer the battery. " +
                    "It jumps by ±5–10 every second by itself."),
            tr("−50…−80 — добре. Aolithium на відео: −66…−70.", "−50…−80 is good. Aolithium in the video: −66…−70."),
            tr("−88 і слабше — підключення зривається (18.10 при −97 — код 133 щоразу). Підійди на 1–3 м.",
                "−88 and weaker — the connection drops (on Oct 18 at −97 — error 133 every time). Move within 1–3 m."))
        e("onelink", Group.LINK, tr("Одне підключення", "One connection"), null,
            tr("Плата тримає лише одне підключення. " +
                "Поки цей застосунок на зв'язку, Aolithium на іншому телефоні не підключиться — і навпаки.",
                "The BMS holds only one connection. While this app is connected, Aolithium on another phone cannot connect — " +
                    "and vice versa."),
            tr("Сховав застосунок — через 5 с зв'язок звільняється.",
                "When you hide the app, the connection is freed after 5 s."),
            tr("Батареї не видно в пошуку — найчастіше її тримає інший телефон.",
                "The battery is not visible in search — most often another phone holds it."))

        // ---------- графіки ----------
        e("outages", Group.CHARTS, tr("Відключення світла", "Power outages"), null,
            tr("Застосунок сам знаходить в історії, коли батарея віддавала струм кілька хвилин поспіль (світла нема) " +
                "і коли знову почала заряджатись чи перестала віддавати (світло повернулось). Для кожного: коли, скільки тривало, " +
                "скільки батарея віддала, з якого відсотка до якого, середня й найбільша потужність.",
                "The app finds in the history when the battery was discharging for several minutes in a row (no power) " +
                    "and when it started charging again or went idle (power came back). For each one: when, how long, " +
                    "how much the battery gave, from what percent to what, average and peak power."),
            tr("Видно тільки ті години, коли телефон був поруч і записував (зараз — поки застосунок відкритий).",
                "Only the hours when the phone was nearby and recording are shown (now — while the app is open)."),
            tr("Якщо батарея віддає струм і при світлі (наприклад, інвертор так налаштований) — скажи, підлаштуємо правило.",
                "If the battery also discharges when there is power (for example, the inverter is set up that way) — " +
                    "tell us, we will adjust the rule."))
        e("dailyMin", Group.CHARTS, tr("Найнижчий заряд за днями", "Lowest charge by day"), null,
            tr("Для кожного дня — до скількох відсотків опускалась батарея. Видно «хвилі»: з кожним днем нижче — " +
                "пора генератор — знову вгору. Торкнись дня, щоб позначити «Г» — у цей день працював генератор " +
                "(для батареї світло й генератор однакові, сам застосунок їх не розрізнить).",
                "For each day — how low the battery went, in percent. You can see the \"waves\": lower each day — " +
                    "time for the generator — up again. Tap a day to mark it \"G\" — the generator ran that day " +
                    "(for the battery, grid power and a generator look the same, the app cannot tell them apart by itself)."),
            tr("Стовпчики в зеленій зоні (вище 20 %).", "Bars in the green zone (above 20%)."),
            tr("Щодня нижче й нижче — батарея не встигає зарядитись між відключеннями.",
                "Lower and lower every day — the battery does not have time to charge between outages."))
        e("laggard", Group.CELLS, tr("Хто відстає наприкінці заряду", "Lagging cell at end of charge"), null,
            tr("Наприкінці кожного заряду дивимось, яка комірка найнижча, коли найвища вже вгорі. Часто це одна й та сама: " +
                "вона трохи «менша» або мала менший заряд. Плата вирівнює її балансуванням.",
                "At the end of each charge we check which cell is the lowest when the highest is already at the top. " +
                    "Often it is the same one: it is a bit \"smaller\" or had less charge. The BMS evens it out by balancing."),
            tr("Одна комірка відстає, а різниця в кінці заряду не росте від тижня до тижня — нормально.",
                "One cell lags, but the difference at the end of charge does not grow from week to week — normal."),
            tr("Різниця в кінці заряду росте (наприклад, 15 → 40 → 80 мВ) — комірка слабне, варто стежити.",
                "The difference at the end of charge grows (for example, 15 → 40 → 80 mV) — the cell is getting weaker, " +
                    "keep an eye on it."))
        e("charger", Group.CHARTS, tr("Зарядний: до скількох вольт доводить", "Charger: how many volts it reaches"), null,
            tr("Найвища напруга батареї під час заряду за останні дні. Для 4 комірок LiFePO4 зарядний зазвичай доводить до " +
                "13,8–14,6 В (3,45–3,65 В на комірку), а 100 % плата ставить, коли комірки доходять до повної.",
                "The highest battery voltage during charging in recent days. For 4 LiFePO4 cells, a charger usually brings it to " +
                    "13.8–14.6 V (3.45–3.65 V per cell), and the BMS sets 100% when the cells reach full."),
            tr("13,8–14,6 В.", "13.8–14.6 V."),
            tr("Вище 14,6 В — забагато, плата часто зупинятиме заряд захистом. Нижче 13,8 В — батарея не доходить до повної, " +
                "і плата рідко уточнює відсоток. Це орієнтир, не діагноз.",
                "Above 14.6 V — too much, the BMS will often stop charging with a protection. Below 13.8 V — the battery " +
                    "does not reach full, and the BMS rarely corrects the percent. This is a guide, not a diagnosis."))
        e("history", Group.CHARTS, tr("Як записується історія", "How history is recorded"), null,
            tr("Поки застосунок відкритий і на зв'язку, він раз на 10 секунд записує заряд, напругу, струм, температуру й комірки " +
                "у пам'ять телефона (окремо для кожної батареї). З увімкненим «Записом у фоні» («Ще») — ще й раз на ~5 хв, коли " +
                "застосунок закритий. Інтернет для цього не потрібен, історія нікуди не передається. Зберігається 90 днів. «Зберегти CSV» — уся " +
                "історія батареї одним файлом-таблицею (відкривається в Таблицях Google чи Excel).",
                "While the app is open and connected, every 10 seconds it records charge, voltage, current, temperature " +
                    "and cells to the phone memory (separately for each battery). With \"Background recording\" on (\"More\"), " +
                    "also every ~5 min when the app is closed. No internet is needed for this, history is not sent anywhere. " +
                    "Kept for 90 days. \"Save CSV\" — the whole battery history in one table file " +
                    "(opens in Google Sheets or Excel)."),
            tr("Розрив на графіку — запису не було понад 12 хв (застосунок закритий і фон вимкнений, або батарея далеко).",
                "A gap on the chart — nothing was recorded for over 12 min (the app was closed and background recording was off, " +
                    "or the battery was far away)."),
            "—")
        e("background", Group.CHARTS, tr("Запис у фоні", "Background recording"), null,
            tr("Раз на ~5 хв, навіть коли застосунок закритий, він на кілька секунд підключається до батареї, читає показники, " +
                "записує в історію й відключається — батарея знову вільна (Aolithium на іншому телефоні теж підключиться). " +
                "Поки запис увімкнено, у шторці висить тихе сповіщення «Запис у фоні» з останніми числами — без нього Android " +
                "зупиняє фонову роботу. Кнопка «Вимкнути» в сповіщенні вимикає запис для всіх батарей. Плату застосунок і тут лише читає.",
                "Every ~5 min, even when the app is closed, it connects to the battery for a few seconds, reads the values, " +
                    "writes them to history and disconnects — the battery is free again (Aolithium on another phone can connect too). " +
                    "While recording is on, a quiet \"Background recording\" notification with the latest numbers stays " +
                    "in the notification shade — without it Android stops background work. The \"Turn off\" button " +
                    "in the notification turns off recording for all batteries. Here too the app only reads the BMS."),
            tr("Уночі, коли телефон лежить, Android будить застосунки рідше — точки можуть іти раз на 10 хв. Якщо екран цієї " +
                "батареї відкритий, фон її не чіпає (екран сам пише раз на 10 с).",
                "At night, when the phone lies still, Android wakes apps less often — points may come every 10 min. " +
                    "If this battery's screen is open, background recording leaves it alone (the screen itself records every 10 s)."),
            tr("«Нема зв'язку» щоразу — батарея далеко (Bluetooth дістає кілька метрів) або зайнята іншим телефоном. Записів " +
                "немає годинами — Android приспав застосунок: у «Ще» натисни «Не обмежувати у фоні».",
                "\"No connection\" every time — the battery is far away (Bluetooth reaches a few meters) or busy with another phone. " +
                    "No records for hours — Android put the app to sleep: in \"More\" tap \"Don't restrict in background\"."))
        e("alerts", Group.CHARTS, tr("Сповіщення", "Notifications"), null,
            tr("Застосунок сам скаже в шторці: світло зникло (батарея віддає вже кілька хвилин — скільки вистачить до 20 %) і " +
                "повернулось (скільки не було, скільки витрачено); мало заряду — 20 %, 10 %, 5 %; батарея повна (щоб вчасно " +
                "вимкнути генератор); плата вимкнула заряд чи розряд або спрацював захист — і коли захист знято; велика різниця " +
                "комірок (у середині заряду від 30 мВ, будь-де до 95 % — від 100 мВ); холод нижче 0 °C і спека вище 50 °C; " +
                "тихе «Нема даних», якщо годину не було зв'язку. Кожне — один раз, доки стан не мине; кожне вмикається окремо.",
                "The app tells you in the notification shade: power went out (the battery has been discharging for a few minutes — " +
                    "how long it lasts to 20%) and came back (how long it was out, how much was used); low charge — 20%, 10%, 5%; " +
                    "battery full (to turn off the generator in time); the BMS turned off charge or discharge, or a protection " +
                    "tripped — and when the protection clears; big cell difference (mid-charge from 30 mV, anywhere below 95% — " +
                    "from 100 mV); cold below 0 °C and heat above 50 °C; a quiet \"No data\" if there was no connection " +
                    "for an hour. Each one comes once, until the state passes; each can be turned on separately."),
            tr("Спирається на те, що бачить застосунок: коли відкритий екран батареї, і раз на ~5 хв з увімкненим «Записом у фоні». " +
                "Bluetooth дістає лише кілька метрів — телефон далеко від батареї, і сповіщень про світло й заряд нема, доки не " +
                "повернешся. «Перезаряд комірки» наприкінці заряду — звичайна робота плати, тому це «Батарея повна», а не тривога.",
                "It works from what the app sees: when the battery screen is open, and every ~5 min with \"Background recording\" on. " +
                    "Bluetooth reaches only a few meters — if the phone is far from the battery, there are no power and charge " +
                    "notifications until you come back. \"Cell overcharge\" at the end of charge is normal BMS work, " +
                    "so it shows as \"Battery full\", not an alarm."),
            tr("Сповіщень нема зовсім — Android заборонив їх застосунку («Дозволити сповіщення» тут) або вид вимкнено. Звук кожного " +
                "виду можна змінити в налаштуваннях телефона: Застосунки → Aolithium Live → Сповіщення.",
                "No notifications at all — Android blocked them for the app (\"Allow notifications\" here) or that type is turned off. " +
                    "You can change the sound of each type in the phone settings: Apps → Aolithium Live → Notifications."))
        return list
    }

    val ALL: List<Entry> get() = list()

    fun get(id: String): Entry? = list().firstOrNull { it.id == id }

    fun byGroup(): Map<Group, List<Entry>> = list().groupBy { it.group }

    /**
     * Лічильник (назва з протоколу) → запис довідника. Назви — ключі з `JbdParser.ERROR_COUNT_NAMES`, не текст для людини.
     * T12: якщо назви там стануть двомовними, шукаємо ще й за номером у протоколі (порядок фіксований).
     */
    fun forCounter(name: String): String = when (name) {
        "Перезаряд комірки" -> "cnt_cell_over" // uk-only: ключ із протоколу
        "Перерозряд комірки" -> "cnt_cell_under" // uk-only: ключ із протоколу
        "Завеликий струм заряду", "Завеликий струм розряду" -> "cnt_overcurrent" // uk-only: ключ із протоколу
        "Коротке замикання" -> "cnt_short" // uk-only: ключ із протоколу
        "Гаряче для заряду", "Холодно для заряду", "Гаряче для розряду", "Холодно для розряду" -> "cnt_temp" // uk-only: ключ
        else -> when (JbdParser.ERROR_COUNT_NAMES.indexOf(name)) {
            0 -> "cnt_short"
            1, 2 -> "cnt_overcurrent"
            3 -> "cnt_cell_over"
            4 -> "cnt_cell_under"
            5, 6, 7, 8 -> "cnt_temp"
            else -> "counters"
        }
    }
}

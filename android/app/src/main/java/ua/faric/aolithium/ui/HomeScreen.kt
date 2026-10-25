package ua.faric.aolithium.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import ua.faric.aolithium.ble.BleScanner
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.core.Signal
import ua.faric.aolithium.core.UpdateFeature
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.Batteries
import ua.faric.aolithium.data.SignalStore

/**
 * Головна: що дозволити (якщо чогось бракує), «Мої батареї», «Шукати поруч», унизу — «Оновлення застосунку»
 * (T37; не для батареї, а для застосунку; ховається перемикачем [UpdateFeature.VISIBLE]).
 * Альбомна (≥ 600 dp) — дві колонки: ліворуч мої батареї, праворуч пошук і оновлення.
 */
class HomeScreen(host: MainActivity, state: Bundle?) : Screen(host), BleScanner.Listener {
    private val batteries = Batteries(host)
    private val scanner = BleScanner(host)
    private var showOthers = state?.getBoolean(KEY_OTHERS) ?: false
    private var scanError: String? = null
    private var scannedOnce = state?.getBoolean(KEY_SCANNED) ?: false

    private val accessBox = ui.column(12)
    private val savedBox = ui.column(10)
    private val scanBox = ui.column(10)
    private val updateBox = ui.column(10)
    override val view: View

    init {
        val left = ui.column(14)
        left.addView(accessBox, ui.matchWrap())
        left.addView(savedBox, ui.matchWrap())
        val right = ui.column(14).apply { addView(scanBox, ui.matchWrap()) }
        if (UpdateFeature.VISIBLE) right.addView(updateBox, ui.matchWrap())
        val body: View = if (ui.twoPane) {
            ui.row(16).apply {
                gravity = Gravity.TOP
                addView(left, ui.weight(1f))
                addView(right, ui.weight(1f))
            }
        } else {
            ui.column(14).apply {
                addView(left, ui.matchWrap())
                addView(right, ui.matchWrap())
                addView(ui.muted(host.versionLine, 12f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
            }
        }
        view = ui.scroll(body)
        renderAll()
    }

    override fun onResume() = renderAll()

    override fun onAccessChanged() = renderAll()

    override fun onStop() {
        scanner.stop()
    }

    override fun save(out: Bundle) {
        out.putBoolean(KEY_OTHERS, showOthers)
        out.putBoolean(KEY_SCANNED, scannedOnce)
    }

    private fun renderAll() {
        renderAccess()
        renderSaved()
        renderScan()
        renderUpdate()
    }

    // ---------- оновлення застосунку (T37) ----------

    fun renderUpdate() {
        updateBox.removeAllViews()
        if (!UpdateFeature.VISIBLE) return
        updateBox.addView(UpdateUi(host).card(), ui.matchWrap())
    }

    // ---------- що дозволити ----------

    private fun renderAccess() {
        accessBox.removeAllViews()
        val needs = BtAccess.problems(host)
        if (needs.isEmpty()) return
        val card = ui.card(fill = Palette.warningFill, stroke = Palette.warning)
        card.addView(ui.label(tr("Що потрібно, щоб бачити батарею", "What is needed to see the battery"), LineIcon.BLUETOOTH))
        for (n in needs) {
            card.addView(ui.text(n.title, 16f, Palette.text, medium = true))
            card.addView(ui.muted(n.explain, 14f))
        }
        if (BtAccess.Need.NO_BLE !in needs) {
            val buttons = ArrayList<View>()
            when {
                BtAccess.Need.PERMISSION in needs ->
                    buttons += ui.button(tr("Дозволити", "Allow"), Ui.Kind.PRIMARY) { host.requestAccess() }
                BtAccess.Need.BLUETOOTH_OFF in needs ->
                    buttons += ui.button(tr("Увімкнути Bluetooth", "Turn on Bluetooth"), Ui.Kind.PRIMARY) { host.enableBluetooth() }
                BtAccess.Need.LOCATION_OFF in needs ->
                    buttons += ui.button(tr("Увімкнути геолокацію", "Turn on location"), Ui.Kind.PRIMARY) { host.openLocationSettings() }
            }
            if (BtAccess.Need.PERMISSION in needs) {
                buttons += ui.button(tr("Налаштування застосунку", "App settings"), Ui.Kind.QUIET) { host.openAppSettings() }
            }
            card.addView(ui.grid(buttons), ui.matchWrap())
        }
        accessBox.addView(card, ui.matchWrap())
    }

    // ---------- мої батареї ----------

    private fun renderSaved() {
        savedBox.removeAllViews()
        val card = ui.card()
        card.addView(ui.label(tr("Мої батареї", "My batteries"), LineIcon.BATTERY))
        val list = batteries.all()
        if (list.isEmpty()) {
            card.addView(ui.muted(tr(
                "Ще жодної. Натисни «Шукати батареї поруч» — і вибери свою (у Aolithium вона називається на кшталт «AL12-100-4S…»).",
                "None yet. Tap \"Find batteries nearby\" and pick yours (in Aolithium it is named like \"AL12-100-4S…\").",
            ), 14f))
        }
        val signals = SignalStore(host)
        val now = System.currentTimeMillis()
        for (b in list) {
            // T81 (faric 25.10): під кожною батареєю — останній сигнал з ефіру (фонове слухання чи пошук) і коли.
            val s = if (b.address.startsWith("demo:")) null else signals.get(b.address)
            val sub = kindLine(b.kind, b.address) + "\n" + Signal.seen(s?.rssi, s?.at, now)
            card.addView(batteryRow(b.title, sub) { host.openBattery(b.address) }, ui.matchWrap())
        }
        savedBox.addView(card, ui.matchWrap())
    }

    // ---------- пошук ----------

    private fun renderScan() {
        scanBox.removeAllViews()
        val card = ui.card()
        card.addView(ui.label(tr("Пошук поруч", "Search nearby"), LineIcon.SEARCH))
        val ready = BtAccess.problems(host).isEmpty()
        val button = if (scanner.isScanning) {
            val sec = BleScanner.SCAN_MS / 1000
            ui.button(tr("Шукаю… (до $sec с) — зупинити", "Searching… (up to $sec s), stop"), Ui.Kind.NORMAL) {
                scanner.stop()
                renderScan()
            }
        } else {
            ui.iconButton(LineIcon.SEARCH, tr("Шукати батареї поруч", "Find batteries nearby"), Ui.Kind.PRIMARY, large = true) {
                scanError = null
                scannedOnce = true
                scanner.start(this)
                renderScan()
            }
        }
        ui.setEnabled(button, ready)
        card.addView(button, ui.matchWrap())
        if (!ready) card.addView(ui.muted(tr("Спершу дозволь Bluetooth (картка вгорі).", "First allow Bluetooth (card at the top)."), 13f))
        scanError?.let { card.addView(ui.text(it, 14f, Palette.danger)) }

        val results = scanner.results
        val batteriesFound = results.filter { it.kind != BmsMatch.Kind.OTHER }
        val others = results.filter { it.kind == BmsMatch.Kind.OTHER }
        for (f in batteriesFound) {
            val sub = kindLine(f.kind, f.address) + tr(" · сигнал ", " · signal ") + Signal.describe(f.rssi)
            card.addView(batteryRow(f.name ?: NO_NAME, sub) { connectTo(f) }, ui.matchWrap())
            if (Signal.tooFar(f.rssi)) card.addView(ui.text(FAR_HINT, 14f, Palette.warning))
        }
        if (scannedOnce && !scanner.isScanning && batteriesFound.isEmpty()) {
            card.addView(ui.muted(NOTHING_FOUND, 14f))
        }
        if (others.isNotEmpty()) {
            val othersText = if (showOthers) tr("Сховати інші пристрої", "Hide other devices")
            else tr("Інші пристрої поруч: ${others.size}", "Other devices nearby: ${others.size}")
            card.addView(ui.button(othersText, Ui.Kind.NORMAL) {
                showOthers = !showOthers
                renderScan()
            }, ui.matchWrap())
            if (showOthers) {
                card.addView(ui.muted(tr(
                    "Це не схоже на плату батареї. Підключатись — лише якщо точно знаєш, що це вона: " +
                        "застосунок тільки читає, нічого не змінює.",
                    "This does not look like a battery BMS. Connect only if you are sure it is one. " +
                        "The app only reads, it changes nothing.",
                ), 13f))
                for (f in others) {
                    val sub = BmsMatch.shortAddress(f.address) + tr(" · сигнал ", " · signal ") + Signal.describe(f.rssi)
                    card.addView(batteryRow(f.name ?: NO_NAME, sub) { connectTo(f) }, ui.matchWrap())
                }
            }
        }
        scanBox.addView(card, ui.matchWrap())
    }

    private fun connectTo(f: BleScanner.Found) {
        scanner.stop()
        f.name?.let { Batteries.scanNames[f.address] = it }
        Batteries.scanKinds[f.address] = f.kind // v0.4: JK BMS відкривається своїм зв'язком (JkLink)
        host.openBattery(f.address)
    }

    private fun kindLine(kind: BmsMatch.Kind, address: String) = kind.title + " · " + BmsMatch.shortAddress(address)

    /** Рядок-плитка: назва, підпис, вся плитка натискається. */
    private fun batteryRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = ui.row(12).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            minimumHeight = ui.dp(Ui.MIN_TOUCH_DP + 8)
            background = ui.pressable(ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = "$title. $sub"
        }
        val icon = android.widget.ImageView(host).apply { setImageDrawable(LineIcon.BATTERY.drawable(Palette.accent, ui.dp(28))) }
        row.addView(icon, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        val texts = ui.column(2)
        texts.addView(ui.text(title, 16f, Palette.text, medium = true))
        texts.addView(ui.muted(sub, 12.5f))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.text("›", 24f, Palette.muted))
        return row
    }

    // ---------- BleScanner.Listener ----------

    override fun onFound(all: List<BleScanner.Found>) = renderScan()

    override fun onFinished(error: String?) {
        scanError = error
        renderScan()
    }

    private companion object {
        val FAR_HINT: String
            get() = tr(
                "Сигнал слабкий — батарея далеко або за стіною. З таким сигналом підключення " +
                    "зазвичай зривається. Підійди ближче (1–3 м) і пошукай ще раз.",
                "Weak signal: the battery is far or behind a wall. With such a signal the connection " +
                    "usually drops. Move closer (1–3 m) and search again.",
            )
        const val KEY_OTHERS = "others"
        const val KEY_SCANNED = "scanned"
        val NOTHING_FOUND: String
            get() = tr(
                "Батареї не знайдено. Перевір: батарея поруч (до 5–10 м); застосунок Aolithium на інших " +
                    "телефонах закритий — поки плата з кимось на зв'язку, її не видно в пошуку.",
                "No batteries found. Check: the battery is near (within 5–10 m); the Aolithium app is closed on other " +
                    "phones. While the BMS is connected to someone, search cannot see it.",
            )
        val NO_NAME: String get() = tr("Без назви", "No name")
    }
}

package ua.faric.aolithium.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import ua.faric.aolithium.ble.BleScanner
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.core.BmsMatch
import ua.faric.aolithium.data.Batteries

/**
 * Головна: що дозволити (якщо чогось бракує), «Мої батареї», «Шукати поруч».
 * Альбомна (≥ 600 dp) — дві колонки: ліворуч мої батареї, праворуч пошук.
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
    override val view: View

    init {
        val left = ui.column(14)
        left.addView(accessBox, ui.matchWrap())
        left.addView(savedBox, ui.matchWrap())
        val right = ui.column(14).apply { addView(scanBox, ui.matchWrap()) }
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
    }

    // ---------- що дозволити ----------

    private fun renderAccess() {
        accessBox.removeAllViews()
        val needs = BtAccess.problems(host)
        if (needs.isEmpty()) return
        val card = ui.card(fill = Palette.warningFill, stroke = Palette.warning)
        card.addView(ui.label("Що потрібно, щоб бачити батарею", LineIcon.BLUETOOTH))
        for (n in needs) {
            card.addView(ui.text(n.title, 16f, Palette.text, medium = true))
            card.addView(ui.muted(n.explain, 14f))
        }
        if (BtAccess.Need.NO_BLE !in needs) {
            val row = FlowLayout(host, ui.dp(8), ui.dp(8))
            when {
                BtAccess.Need.PERMISSION in needs -> row.addView(ui.button("Дозволити", Ui.Kind.PRIMARY) { host.requestAccess() })
                BtAccess.Need.BLUETOOTH_OFF in needs -> row.addView(ui.button("Увімкнути Bluetooth", Ui.Kind.PRIMARY) { host.enableBluetooth() })
                BtAccess.Need.LOCATION_OFF in needs -> row.addView(ui.button("Увімкнути геолокацію", Ui.Kind.PRIMARY) { host.openLocationSettings() })
            }
            if (BtAccess.Need.PERMISSION in needs) row.addView(ui.button("Налаштування застосунку", Ui.Kind.QUIET) { host.openAppSettings() })
            card.addView(row, ui.matchWrap())
        }
        accessBox.addView(card, ui.matchWrap())
    }

    // ---------- мої батареї ----------

    private fun renderSaved() {
        savedBox.removeAllViews()
        val card = ui.card()
        card.addView(ui.label("Мої батареї", LineIcon.BATTERY))
        val list = batteries.all()
        if (list.isEmpty()) {
            card.addView(ui.muted("Ще жодної. Натисни «Шукати батареї поруч» — і вибери свою (у Aolithium вона називається на кшталт «AL12-100-4S…»).", 14f))
        }
        for (b in list) {
            card.addView(batteryRow(b.title, kindLine(b.kind, b.address)) { host.openBattery(b.address) }, ui.matchWrap())
        }
        savedBox.addView(card, ui.matchWrap())
    }

    // ---------- пошук ----------

    private fun renderScan() {
        scanBox.removeAllViews()
        val card = ui.card()
        card.addView(ui.label("Пошук поруч", LineIcon.SEARCH))
        val ready = BtAccess.problems(host).isEmpty()
        val button = if (scanner.isScanning) {
            ui.button("Шукаю… (до ${BleScanner.SCAN_MS / 1000} с) — зупинити", Ui.Kind.NORMAL) { scanner.stop(); renderScan() }
        } else {
            ui.iconButton(LineIcon.SEARCH, "Шукати батареї поруч", Ui.Kind.PRIMARY, large = true) {
                scanError = null
                scannedOnce = true
                scanner.start(this)
                renderScan()
            }
        }
        ui.setEnabled(button, ready)
        card.addView(button, ui.matchWrap())
        if (!ready) card.addView(ui.muted("Спершу дозволь Bluetooth (картка вгорі).", 13f))
        scanError?.let { card.addView(ui.text(it, 14f, Palette.danger)) }

        val results = scanner.results
        val batteriesFound = results.filter { it.kind != BmsMatch.Kind.OTHER }
        val others = results.filter { it.kind == BmsMatch.Kind.OTHER }
        for (f in batteriesFound) {
            val sub = kindLine(f.kind, f.address) + " · сигнал ${f.rssi} dBm"
            card.addView(batteryRow(f.name ?: "Без назви", sub) { connectTo(f) }, ui.matchWrap())
        }
        if (scannedOnce && !scanner.isScanning && batteriesFound.isEmpty()) {
            card.addView(ui.muted(NOTHING_FOUND, 14f))
        }
        if (others.isNotEmpty()) {
            card.addView(ui.button(if (showOthers) "Сховати інші пристрої" else "Інші пристрої поруч: ${others.size}", Ui.Kind.QUIET) {
                showOthers = !showOthers
                renderScan()
            }, ui.matchWrap())
            if (showOthers) {
                card.addView(ui.muted("Це не схоже на плату батареї. Підключатись — лише якщо точно знаєш, що це вона: застосунок тільки читає, нічого не змінює.", 13f))
                for (f in others) {
                    card.addView(batteryRow(f.name ?: "Без назви", BmsMatch.shortAddress(f.address) + " · ${f.rssi} dBm") { connectTo(f) }, ui.matchWrap())
                }
            }
        }
        scanBox.addView(card, ui.matchWrap())
    }

    private fun connectTo(f: BleScanner.Found) {
        if (f.kind == BmsMatch.Kind.JK) {
            host.toast("JK BMS (Logic Power) — буде у версії 0.4, коли будеш біля батареї в мами.")
            return
        }
        scanner.stop()
        f.name?.let { Batteries.scanNames[f.address] = it }
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
        const val KEY_OTHERS = "others"
        const val KEY_SCANNED = "scanned"
        const val NOTHING_FOUND = "Батареї не знайдено. Перевір: батарея поруч (до 5–10 м); застосунок Aolithium на інших " +
            "телефонах закритий — поки плата з кимось на зв'язку, її не видно в пошуку."
    }
}

package ua.faric.aolithium.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import ua.faric.aolithium.BuildConfig
import ua.faric.aolithium.bg.Notifier
import ua.faric.aolithium.bg.RecordService
import ua.faric.aolithium.ble.BtAccess
import ua.faric.aolithium.core.Lang
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.data.LangSettings
import ua.faric.aolithium.widget.Widgets

/**
 * Одне вікно, три сторінки: «Батареї» (головна), «Батарея» (живі показники), «Інструкція» (T7, `GuideScreen`;
 * сторінка зветься `HELP` — так її відкривають і знімки CI).
 * Поворот перестворює вікно, але сторінка, відкрите вікно і стан екрана відновлюються,
 * а зв'язок з батареєю не рветься (`LinkHolder`).
 */
class MainActivity : Activity() {
    enum class Page { HOME, BATTERY, HELP }

    lateinit var ui: Ui
        private set
    lateinit var overlay: Overlay
        private set

    private lateinit var root: FrameLayout
    private lateinit var frame: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var content: FrameLayout
    private var page = Page.HOME
    private var batteryAddress: String? = null
    /**
     * Звідки відкрили батарею (T26, faric 20.10 00:52): з «Довідки» (демо) — «Назад» веде назад у «Довідку», на те саме
     * місце, а не на головну, щоб подивитись наступне демо без пошуку.
     */
    private var returnTo: Page? = null
    /** Розділ інструкції, який відкрити (напр. «background»); null — зміст або там, де була. */
    private var helpSection: String? = null
    /**
     * Відкрити інструкцію наново (на [helpSection]), а не там, де залишили. Стан скидаємо в [show] уже ПІСЛЯ того, як
     * збережено стан поточного екрана: інакше, коли інструкція відкрита й просять інший розділ, вона щойно зберегла б
     * себе і відкрилась на старому місці (знімки CI p11c/p11e, 20.10).
     */
    private var freshHelp = false
    private var screen: Screen? = null
    private val screenStates = Bundle()
    private var started = false
    private var backCallback: Any? = null
    private var backRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Лише debug-збірка (знімки CI, T12): `am start --es lang en` після force-stop — вікно одразу англійською.
        if (BuildConfig.DEBUG && savedInstanceState == null) intent?.getStringExtra(EXTRA_LANG)?.let { LangSettings.save(this, Lang.of(it)) }
        ui = Ui(this)
        savedInstanceState?.let { s ->
            page = s.getString(KEY_PAGE)?.let { n -> Page.entries.firstOrNull { it.name == n } } ?: Page.HOME
            batteryAddress = s.getString(KEY_ADDRESS)
            returnTo = s.getString(KEY_RETURN)?.let { n -> Page.entries.firstOrNull { it.name == n } }
            s.getBundle(KEY_SCREENS)?.let { screenStates.putAll(it) }
        }
        buildChrome()
        if (page == Page.BATTERY && batteryAddress == null) page = Page.HOME
        show(page, batteryAddress)
        savedInstanceState?.getString(KEY_DIALOG)?.let { screen?.restoreDialog(it) }
        if (savedInstanceState == null) applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    /**
     * Торкнулись сповіщення чи віджета (v0.3) — відкрити ту батарею. Решта — лише debug-збірка (CI):
     * відкрити демо-батарею чи сторінку для знімків екрана.
     */
    private fun applyIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra(EXTRA_BATTERY)?.takeIf { it.isNotBlank() && !it.startsWith("demo:") }?.let {
            i.removeExtra(EXTRA_BATTERY)
            openBattery(it)
        }
        if (!BuildConfig.DEBUG) return
        i.getStringExtra(EXTRA_DEMO)?.let { openBattery("demo:$it") }
        i.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name } }?.let {
            if (it == Page.HELP) openHelp(i.getStringExtra(EXTRA_SECTION) ?: "home") else if (it != Page.BATTERY) show(it, null)
        }
        i.getStringExtra(EXTRA_DIALOG)?.let { screen?.restoreDialog(it) }
    }

    // ---------- каркас ----------

    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        frame = ui.column()
        topBar = ui.row(10).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(4)) }
        content = FrameLayout(this)
        frame.addView(topBar, ui.matchWrap())
        frame.addView(content, ui.weight(1f, vertical = true))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = Overlay(root, ui) { updateBackCallback() }
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 15+ (ціль 36) завжди малює під системними смужками — відступи робимо самі.
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                frame.setPadding(b.left, b.top, b.right, b.bottom)
                overlay.setSystemBars(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
    }

    private fun renderTopBar() {
        topBar.removeAllViews()
        if (page == Page.HOME) {
            val brand = ui.row(8).apply { gravity = Gravity.CENTER_VERTICAL }
            brand.addView(View(this).apply { background = ui.rounded(Palette.accent, radiusDp = 6) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
            // T34 (faric 20.10 06:51): назва трохи більша, а місце для неї — не стисканням шрифту, а меншими кнопками.
            brand.addView(ui.fitOneLine(ui.text("Aolithium Live", 21f, Palette.text, medium = true), 15f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topBar.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // T12/T34: перемикач мови — коротка назва ІНШОЇ мови: «EN» / «UA» (опис для читалки — повна назва її ж мовою).
            val square = LinearLayout.LayoutParams(ui.dp(SQUARE_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
            topBar.addView(ui.squareButton(if (Lang.isEn) "UA" else "EN") {
                setLanguage(if (Lang.isEn) Lang.Code.UK else Lang.Code.EN)
            }.apply { contentDescription = if (Lang.isEn) "Українська" else "English" }, square) // uk-only — назва мови її ж мовою
            // T34: замість «Довідка» — лише значок ?, тієї ж ширини, що й кнопка мови. Відкриває інструкцію (T7).
            topBar.addView(ui.squareButton(ui.iconOnly(LineIcon.HELP, Palette.muted, SQUARE_ICON_DP)) { openHelp() }
                .apply { contentDescription = tr("Інструкція", "Guide") }, square)
        } else {
            val toHelp = page == Page.BATTERY && returnTo == Page.HELP
            topBar.addView(ui.iconButton(LineIcon.BACK, if (toHelp) tr("Інструкція", "Guide") else tr("Батареї", "Batteries"), Ui.Kind.QUIET) { goBack() })
            topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            // T35 (faric 20.10, перевірка 31): мова інструкції — тут, у рядку з «Назад», а не поруч з назвою на сторінці.
            (screen as? GuideScreen)?.let { g ->
                val square = LinearLayout.LayoutParams(ui.dp(SQUARE_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                for (code in listOf(Lang.Code.UK, Lang.Code.EN)) {
                    val label = if (code == Lang.Code.UK) "UA" else "EN"
                    topBar.addView(ui.squareButton(label) {
                        g.setGuideLang(code)
                        renderTopBar()
                    }.apply {
                        if (g.guideLang == code) ui.markSelected(this)
                        contentDescription = if (code == Lang.Code.UK) "Українська" else "English" // uk-only — назва мови її ж мовою
                    }, square)
                }
            }
        }
        topBar.gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * T12: змінити мову. Зберегти, перейменувати канали сповіщень, перемалювати віджети й шторку «Запис у фоні», а
     * вікно перестворити — сторінка, відкрита батарея й прокрутка відновлюються так само, як після повороту.
     */
    fun setLanguage(code: Lang.Code) {
        if (code == Lang.current) return
        LangSettings.save(this, code)
        Notifier.refreshChannels(this)
        Widgets.refresh(this)
        if (RecordService.running) RecordService.ensure(this)
        recreate()
    }

    fun openBattery(address: String) = show(Page.BATTERY, address)

    fun goHome() = show(Page.HOME, null)

    /** «Назад» (кнопка вгорі й системна): батарея, відкрита з інструкції (демо), → інструкція; решта → головна. */
    private fun goBack() {
        if (page == Page.BATTERY && returnTo == Page.HELP) show(Page.HELP, null) else goHome()
    }

    /** Інструкція. [section] — відкрити одразу цей розділ («home» — зміст); null — там, де її залишили. */
    fun openHelp(section: String? = null) {
        helpSection = section?.takeIf { it != "home" }
        freshHelp = section != null
        show(Page.HELP, null)
    }

    private fun show(p: Page, address: String?) {
        screen?.let { s ->
            val b = Bundle()
            s.save(b)
            screenStates.putBundle(stateKey(page, batteryAddress), b)
            if (started) s.onStop()
        }
        if (::overlay.isInitialized && overlay.isShowing) overlay.dismiss(notify = false)
        // Перший показ після повороту (show(page) з onCreate) — returnTo вже відновлено, не чіпаємо.
        if (p != page || screen != null) returnTo = if (p == Page.BATTERY && page == Page.HELP) Page.HELP else null
        page = p
        batteryAddress = if (p == Page.BATTERY) address else null
        if (p == Page.HELP && freshHelp) {
            screenStates.remove(stateKey(p, null))
            freshHelp = false
        }
        val state = screenStates.getBundle(stateKey(p, batteryAddress))
        val s = when (p) {
            Page.HOME -> HomeScreen(this, state)
            Page.BATTERY -> BatteryScreen(this, address ?: "", state)
            Page.HELP -> GuideScreen(this, state, helpSection)
        }
        screen = s
        renderTopBar()
        content.removeAllViews()
        content.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (started) {
            s.onStart()
            s.onResume()
        }
        updateBackCallback()
    }

    private fun stateKey(p: Page, address: String?) = if (p == Page.BATTERY) "BATTERY:$address" else p.name

    // ---------- «Назад» (Android 16 не кличе onBackPressed — реєструємо свій обробник) ----------

    private val wantsBack: Boolean get() = (::overlay.isInitialized && overlay.isShowing) || page != Page.HOME

    private fun handleBack() {
        when {
            overlay.isShowing -> overlay.dismiss()
            screen?.onBack() == true -> Unit
            page != Page.HOME -> goBack()
        }
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = wantsBack
        if (want == backRegistered) return
        val cb = (backCallback as? OnBackInvokedCallback) ?: OnBackInvokedCallback { handleBack() }.also { backCallback = it }
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    @Deprecated("Android 13+: OnBackInvokedCallback")
    override fun onBackPressed() {
        if (wantsBack) handleBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- дозволи й Bluetooth ----------

    fun requestAccess() {
        val missing = BtAccess.missingPermissions(this)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        if (!BtAccess.bluetoothOn(this)) {
            enableBluetooth()
            return
        }
        if (!BtAccess.locationOn(this)) openLocationSettings()
    }

    fun enableBluetooth() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BLUETOOTH)
        } catch (e: SecurityException) {
            requestPermissions(BtAccess.runtimePermissions(), REQ_PERMS)
        } catch (e: ActivityNotFoundException) {
            toast(tr("Увімкни Bluetooth у шторці телефона", "Turn on Bluetooth in the phone's quick settings"))
        }
    }

    fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            toast(tr("Увімкни геолокацію в шторці телефона", "Turn on location in the phone's quick settings"))
        }
    }

    fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            toast(tr("Налаштування → Застосунки → Aolithium Live → Дозволи", "Settings → Apps → Aolithium Live → Permissions"))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        screen?.onAccessChanged()
        if (requestCode == REQ_PERMS && BtAccess.missingPermissions(this).isEmpty() && !BtAccess.bluetoothOn(this)) enableBluetooth()
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE) {
            finishSave(if (resultCode == RESULT_OK) data?.data else null)
            return
        }
        screen?.onAccessChanged()
    }

    // ---------- запис у фоні (v0.2.5) ----------

    /** Android 13+ питає дозвіл на сповіщення; без нього запис працює, але в шторці його не видно. */
    val notificationsAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(PERM_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) requestPermissions(arrayOf(PERM_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    /** Чи не обмежує Android (Samsung — «Батарея: обмежено / оптимізовано») роботу застосунку у фоні. */
    val batteryUnrestricted: Boolean
        get() = (getSystemService(POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(packageName) == true

    /** Системне вікно «Дозволити працювати у фоні без обмежень?». Нема такого — сторінка застосунку в налаштуваннях. */
    fun askUnrestrictedBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        } catch (e: SecurityException) {
            openAppSettings()
        }
    }

    // ---------- зберегти файл (історія CSV) ----------

    private var pendingSave: ((java.io.OutputStream) -> Unit)? = null

    /** Системне вікно «Зберегти як…» (без бібліотек і без дозволу на пам'ять); [write] пише вміст у фоні. */
    fun saveFile(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        pendingSave = write
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SAVE)
        } catch (e: ActivityNotFoundException) {
            pendingSave = null
            toast(tr("На телефоні немає вікна збереження файлів", "This phone has no file save window"))
        }
    }

    private fun finishSave(uri: Uri?) {
        val write = pendingSave
        pendingSave = null
        if (uri == null) return
        if (write == null) {
            // Вікно повернули після повороту екрана — вміст загубився. Чесно кажемо.
            toast(tr("Не збереглося — натисни «Зберегти CSV» ще раз", "Not saved. Tap \"Save CSV\" again"))
            return
        }
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { write(it) } != null
            } catch (e: java.io.IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            runOnUiThread { toast(if (ok) tr("Збережено", "Saved") else tr("Не вдалось зберегти файл", "Could not save the file")) }
        }.start()
    }

    /** Переслати текст (журнал зв'язку) — системне вікно «Поділитися». */
    fun shareText(subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(i, subject))
        } catch (e: ActivityNotFoundException) {
            toast(tr("Немає застосунку, щоб поділитися", "No app to share with"))
        }
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Рядок версії: щоб faric звіряв, що стоїть на телефоні (правило проєкту). */
    val versionLine: String
        get() = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_SHA}"

    // ---------- життєвий цикл ----------

    override fun onStart() {
        super.onStart()
        started = true
        screen?.onStart()
        // Запис у фоні ввімкнено, а службу Android зупинив (оновлення, «Очистити все» в диспетчері задач) —
        // запускаємо знову, поки застосунок на екрані (з фону Android 12+ не дозволяє).
        if (!RecordService.running) RecordService.ensure(this, RecordService.ACTION_TICK)
    }

    override fun onResume() {
        super.onResume()
        screen?.onResume()
    }

    override fun onStop() {
        started = false
        screen?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screen?.let { s ->
            val b = Bundle()
            s.save(b)
            screenStates.putBundle(stateKey(page, batteryAddress), b)
        }
        outState.putString(KEY_PAGE, page.name)
        outState.putString(KEY_ADDRESS, batteryAddress)
        returnTo?.let { outState.putString(KEY_RETURN, it.name) }
        outState.putBundle(KEY_SCREENS, screenStates)
        overlay.key?.let { outState.putString(KEY_DIALOG, it) }
    }

    companion object {
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_PAGE = "page"
        const val EXTRA_DIALOG = "dialog"
        const val EXTRA_LANG = "lang"
        /** Debug (знімки CI): розділ інструкції разом з `page HELP`. */
        const val EXTRA_SECTION = "section"
        /** T34: кнопки мови й інструкції на головній — однакові квадрати. */
        private const val SQUARE_DP = 52
        private const val SQUARE_ICON_DP = 28
        private const val KEY_PAGE = "page"
        private const val KEY_ADDRESS = "address"
        private const val KEY_RETURN = "returnTo"
        private const val KEY_SCREENS = "screens"
        private const val KEY_DIALOG = "dialog"
        private const val REQ_PERMS = 10
        private const val REQ_BLUETOOTH = 20
        private const val REQ_NOTIFICATIONS = 30
        private const val REQ_SAVE = 40
        private const val PERM_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}

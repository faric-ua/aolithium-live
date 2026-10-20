package ua.faric.aolithium.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ua.faric.aolithium.BuildConfig
import ua.faric.aolithium.core.DriveUpdate
import ua.faric.aolithium.core.VersionLine
import ua.faric.aolithium.core.UpdateFeature
import ua.faric.aolithium.core.tr
import ua.faric.aolithium.update.DriveHttp
import ua.faric.aolithium.update.UpdateApkProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Стан оновлення, що переживає перестворення вікна (зміна мови): робота йде у фоновому потоці, а вікно
 * (нове) лише показує поточний текст. Копія з Track Catcher (T37).
 */
object Updater {
    @Volatile var busy = false
    /** Іде тиха автопублікація (T37): поступ — не у вікні, а рядком у картці «Оновлення застосунку». */
    @Volatile var quiet = false
    var progressTitle = ""
    var progressText = ""
    var progressFinished = false
    var verdict: DriveUpdate.Manifest? = null
    var problem: String? = null
    var problemOffersLink = false
    var pendingApk: File? = null
    internal var progressView: TextView? = null
    internal var progressButton: TextView? = null
    /** Рядки стану тихої публікації (картка на головній, вікно публікації). */
    internal val quietViews = ArrayList<TextView>()

    /** Видиме вікно застосунку (між onStart і onStop). */
    private var host: MainActivity? = null
    private val pending = ArrayList<(MainActivity) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun attach(activity: MainActivity) {
        host = activity
        val todo = ArrayList(pending)
        pending.clear()
        todo.forEach { it(activity) }
    }

    fun detach(activity: MainActivity) {
        if (host === activity) host = null
        progressView = null
        progressButton = null
        quietViews.clear()
    }

    /** Виконати в головному потоці на видимому вікні; сховано — коли застосунок відкриють знову. */
    fun onMain(action: (MainActivity) -> Unit) {
        main.post {
            val h = host
            if (h != null && !h.isFinishing && !h.isDestroyed) action(h) else pending += action
        }
    }

    fun setProgress(text: String, finished: Boolean = false) {
        main.post {
            progressText = text
            progressFinished = finished
            progressView?.text = text
            progressButton?.let { b ->
                b.text = if (finished) tr("Закрити", "Close") else tr("Сховати (робота йде далі)", "Hide (work continues)")
            }
            if (quiet) quietViews.forEach { it.text = text }
        }
    }
}

/**
 * «Оновлення застосунку» — картка внизу головної (T37, як у Track Catcher, де вона в «Налаштуваннях»).
 * Для всіх: «Перевірити оновлення» (з Google Диска автора), тиха перевірка раз на день.
 * Для автора: «Опублікувати цю версію на Диск» з перевіркою через інтернет, що файл дійшов, і (своє, T37)
 * «Публікувати автоматично» — при відкритті застосунку, коли встановлена версія новіша за опубліковану.
 * Увесь блок ховається одним перемикачем [UpdateFeature.VISIBLE].
 */
class UpdateUi(private val host: MainActivity) {
    private val ui: Ui get() = host.ui
    private val prefs: SharedPreferences = host.getSharedPreferences("update", Context.MODE_PRIVATE)

    private val installedCode: Long get() = BuildConfig.VERSION_CODE.toLong()
    private val versionText: String get() = VersionLine.text(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), tr("збірка", "build"))

    // ---------- картка на головній ----------

    fun card(): View = ui.card(10).apply {
        val head = ui.row(8)
        head.addView(ui.label(tr("Оновлення застосунку", "App update"), LineIcon.CLOUD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.squareButton(ui.iconOnly(LineIcon.HELP, Palette.muted, 24)) { host.openHelp("update") }
            .apply { contentDescription = tr("Інструкція: оновлення", "Guide: updates") },
            LinearLayout.LayoutParams(ui.dp(Ui.MIN_TOUCH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(head, ui.matchWrap())
        addView(ui.factOneLine(tr("Встановлено", "Installed"), versionText), ui.matchWrap())
        addView(ui.muted(
            if (manifestId() == null) tr(
                "Посилання на оновлення ще не задано — натисни «Перевірити оновлення» й встав посилання від автора.",
                "No update link yet. Tap \"Check for updates\" and paste the link from the author.",
            ) else tr(
                "Нові версії беруться з Google Диска автора. Батареї, історія й налаштування лишаються. Дані батареї нікуди не йдуть.",
                "New versions come from the author's Google Drive. Batteries, history and settings stay. Battery data goes nowhere.",
            ),
            13.5f,
        ))
        addView(ui.iconButton(LineIcon.CLOUD, tr("Перевірити оновлення", "Check for updates"), Ui.Kind.PRIMARY) { check(quiet = false) }, ui.matchWrap())
        if (manifestId() != null) {
            // Track Catcher T54: змінити посилання можна завжди, а не лише після помилки перевірки.
            addView(ui.button(tr("Інше посилання на оновлення", "Another update link"), Ui.Kind.QUIET) { askForLink() }, ui.matchWrap())
        }
        addView(ui.text(tr("Перевіряти самому раз на день", "Check by itself once a day"), 15.5f, Palette.text, medium = true))
        val auto = prefs.getBoolean(KEY_AUTO, true)
        addView(ui.grid(listOf(
            ui.choice(tr("Так", "Yes"), auto) { setAutoCheck(true) },
            ui.choice(tr("Ні", "No"), !auto) { setAutoCheck(false) },
        )), ui.matchWrap())
        addView(ui.muted(tr(
            "Під час відкриття застосунку, без зайвих вікон: скаже лише, якщо є нова версія.",
            "When the app opens, without extra windows: it speaks up only if there is a new version.",
        ), 13f))
        addView(ui.button(tr("Опублікувати цю версію на Диск (автор)", "Publish this version to Drive (author)"), Ui.Kind.NORMAL) { showPublishSheet() }, ui.matchWrap())
        authorStatus()?.let { addView(it, ui.matchWrap()) }
    }

    private fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        host.updateChanged()
    }

    /**
     * Лише на телефоні автора (файли на Диску налаштовані): «Публікувати автоматично: так / ні» і що сталося
     * з останньою публікацією; поки йде тиха автопублікація — її поступ (T37: «результат — у картці»).
     */
    private fun authorStatus(): View? {
        if (prefs.getString(KEY_APK_URI, "").isNullOrBlank()) return null
        val box = ui.column(4)
        val autoOn = prefs.getBoolean(KEY_AUTO_PUBLISH, false)
        box.addView(ui.muted(
            if (autoOn) tr("Автор: публікувати автоматично — так", "Author: publish automatically — yes")
            else tr("Автор: публікувати автоматично — ні", "Author: publish automatically — no"),
            13f,
        ))
        statusLine(13f)?.let { box.addView(it) }
        return box
    }

    /** Поступ тихої публікації або підсумок останньої; null — нічого ще не було. */
    private fun statusLine(sizeSp: Float): TextView? {
        if (Updater.busy && Updater.quiet) {
            Updater.quietViews.removeAll { !it.isAttachedToWindow } // старі картки, вже прибрані з екрана
            return ui.text(Updater.progressText, sizeSp, Palette.accent).also { Updater.quietViews += it }
        }
        val last = prefs.getString(KEY_LAST_RESULT, "").orEmpty()
        if (last.isBlank()) return null
        return ui.text(last, sizeSp, if (prefs.getBoolean(KEY_LAST_OK, false)) Palette.success else Palette.warning)
    }

    /** Відкрили застосунок: автор — тиха автопублікація (якщо пора), інакше — тиха перевірка раз на день. */
    fun onAppOpened() {
        if (!UpdateFeature.VISIBLE) return
        if (!autoPublish()) autoCheck()
    }

    // ---------- автор: публікація ----------

    /** ID файлів на Диску: вставлені автором або вбудовані (0.4.6 — файли створено заздалегідь). */
    private fun apkIdNow(): String = prefs.getString(KEY_APK_ID, "").orEmpty().ifBlank { DriveUpdate.BUILT_IN_APK_ID }
    private fun jsonIdNow(): String = prefs.getString(KEY_JSON_ID, "").orEmpty().ifBlank { DriveUpdate.BUILT_IN_MANIFEST_ID }

    /** Показати кроки 3–4 (вставити інші посилання) і тоді, коли посилання вбудовані. */
    private var linkSteps = false

    private fun setupReady(): Boolean = listOf(KEY_APK_URI, KEY_JSON_URI).all { !prefs.getString(it, "").isNullOrBlank() } &&
        apkIdNow().isNotBlank() && jsonIdNow().isNotBlank()

    fun showPublishSheet(keepScroll: Boolean = false) {
        if (!UpdateFeature.VISIBLE) return
        val apkUri = prefs.getString(KEY_APK_URI, "").orEmpty()
        val jsonUri = prefs.getString(KEY_JSON_URI, "").orEmpty()
        val apkId = apkIdNow()
        val jsonId = jsonIdNow()
        val pick = DriveUpdate.filesBuiltIn
        val apkReady = apkUri.isNotBlank()
        val jsonReady = jsonUri.isNotBlank()
        val apkLink = apkId.isNotBlank()
        val jsonLink = jsonId.isNotBlank()

        val box = ui.column(10)
        box.addView(Overlay.header(ui.title(tr("Публікація версії на Диск", "Publishing a version to Drive"), 19f)))
        box.addView(ui.muted(
            tr("Ця версія: ", "This version: ") + versionText + "\n" + (if (pick) tr(
                "Один раз: вибрати два готові файли на Диску (кроки 1–2), посилання вже вбудовані. " +
                    "Далі після кожної нової версії — лише «Опублікувати зараз» (або «Публікувати автоматично»).",
                "Once: pick the two ready files on Drive (steps 1–2); the links are already built in. " +
                    "After that, for each new version — only \"Publish now\" (or \"Publish automatically\").",
            ) else tr(
                "Один раз: два файли на Диску (кроки 1–2) і посилання на них (кроки 3–4). " +
                    "Далі після кожної нової версії — лише «Опублікувати зараз» (або «Публікувати автоматично»).",
                "Once: two files on Drive (steps 1–2) and links to them (steps 3–4). " +
                    "After that, for each new version — only \"Publish now\" (or \"Publish automatically\").",
            )),
            14f,
        ))
        fun step(label: String, kind: Ui.Kind, enabled: Boolean, action: () -> Unit) {
            val b = ui.button(label, kind) { action() }
            ui.setEnabled(b, enabled)
            box.addView(b, ui.matchWrap())
        }
        val apkName = prefs.getString(KEY_APK_NAME, "").orEmpty()
        val jsonName = prefs.getString(KEY_JSON_NAME, "").orEmpty()
        step(
            if (apkReady) tr("1. Файл APK — готово: $apkName", "1. APK file — done: $apkName")
            else if (pick) tr("1. Вибрати на Диску «${DriveUpdate.APK_FILE_NAME}»", "1. Pick \"${DriveUpdate.APK_FILE_NAME}\" on Drive")
            else tr("1. Створити файл APK на Диску", "1. Create the APK file on Drive"),
            if (apkReady) Ui.Kind.QUIET else Ui.Kind.PRIMARY, !apkReady,
        ) { if (pick) pickFile(REQ_APK) else createFile(REQ_APK, UpdateApkProvider.MIME, DriveUpdate.APK_FILE_NAME) }
        step(
            if (jsonReady) tr("2. Файл версії — готово: $jsonName", "2. Version file — done: $jsonName")
            else if (pick) tr("2. Вибрати на Диску «${DriveUpdate.VERSION_FILE_NAME}»", "2. Pick \"${DriveUpdate.VERSION_FILE_NAME}\" on Drive")
            else tr("2. Створити файл версії на Диску", "2. Create the version file on Drive"),
            if (jsonReady) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && !jsonReady,
        ) { if (pick) pickFile(REQ_JSON) else createFile(REQ_JSON, "application/json", DriveUpdate.VERSION_FILE_NAME) }
        if (pick && linkSteps) {
            // Змінити вбудовані посилання на інші (скопійовані в Google Диску) — для цього телефона.
            step(tr("3. Вставити інше посилання на APK", "3. Paste another APK link"), Ui.Kind.NORMAL, true) {
                if (pasteLink(KEY_APK_ID)) showPublishSheet(keepScroll = true)
            }
            step(tr("4. Вставити інше посилання на файл версії", "4. Paste another version file link"), Ui.Kind.NORMAL, true) {
                if (pasteLink(KEY_JSON_ID)) showPublishSheet(keepScroll = true)
            }
        }
        if (!pick) step(
            if (apkLink) tr("3. Посилання на APK — вставлено", "3. APK link — pasted") else tr("3. Вставити посилання на APK", "3. Paste the APK link"),
            if (apkLink) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && jsonReady && !apkLink,
        ) { if (pasteLink(KEY_APK_ID)) showPublishSheet(keepScroll = true) }
        if (!pick) step(
            if (jsonLink) tr("4. Посилання на файл версії — вставлено", "4. Version file link — pasted") else tr("4. Вставити посилання на файл версії", "4. Paste the version file link"),
            if (jsonLink) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && jsonReady && apkLink && !jsonLink,
        ) { if (pasteLink(KEY_JSON_ID)) showPublishSheet(keepScroll = true) }
        if (pick && !linkSteps) {
            // faric 20.10 16:42: «кнопку, щоб можна було змінити посилання — як у всіх». Вбудовані — за замовчуванням.
            box.addView(ui.button(tr("Змінити посилання на файли (автор)", "Change the file links (author)"), Ui.Kind.QUIET) {
                linkSteps = true
                showPublishSheet(keepScroll = true)
            }, ui.matchWrap())
        }
        if (pick && !(apkReady && jsonReady)) {
            box.addView(ui.muted(tr(
                "Кроки 1–2: у вікні вибору натисни ☰ ліворуч → «Google Диск» → папки ${DriveUpdate.BUILT_IN_FOLDER} → торкнись файлу.",
                "Steps 1–2: in the picker tap ☰ on the left → \"Google Drive\" → folders ${DriveUpdate.BUILT_IN_FOLDER} → tap the file.",
            ), 13f))
        } else if (!pick && !(apkReady && jsonReady && apkLink && jsonLink)) {
            box.addView(ui.muted(tr(
                "Кроки 1–2: у вікні вибору натисни ☰ ліворуч → «Google Диск» → потрібна папка → «Зберегти».\n" +
                    "Кроки 3–4: відкрий Google Диск → ⋮ біля файлу → «Надати доступ» → «Усі, хто має посилання» → " +
                    "«Копіювати посилання» → повернись сюди й натисни крок.",
                "Steps 1–2: in the picker tap ☰ on the left → \"Google Drive\" → the folder you want → \"Save\".\n" +
                    "Steps 3–4: open Google Drive → ⋮ next to the file → \"Share\" → \"Anyone with the link\" → " +
                    "\"Copy link\" → come back here and tap the step.",
            ), 13f))
        }
        if (jsonLink && DriveUpdate.BUILT_IN_MANIFEST_ID.isNotBlank() && jsonId != DriveUpdate.BUILT_IN_MANIFEST_ID) {
            // Track Catcher T47: файл версії створили вдруге — старі телефони дивляться в старий.
            box.addView(ui.text(tr(
                "Увага: файл версії не той, що вбудований у застосунок. Телефони без вставленого посилання цієї " +
                    "публікації не побачать — скажи про це в чаті Aolithium Live (вбудоване посилання треба замінити).",
                "Warning: this version file is not the one built into the app. Phones without a pasted link will not see " +
                    "this publication. Say so in the Aolithium Live chat (the built-in link has to be replaced).",
            ), 13.5f, Palette.warning))
        } else if (jsonLink && DriveUpdate.BUILT_IN_MANIFEST_ID.isBlank()) {
            // T37: перший раз — посилання вставляють вручну; потім його вбудовують у наступну збірку.
            box.addView(ui.text(tr(
                "Посилання ще не вбудоване в застосунок. Натисни «Поділитися посиланням на оновлення» → надішли в чат " +
                    "Aolithium Live: його вбудують у наступну версію, і інші телефони нічого не вставлятимуть.",
                "The link is not built into the app yet. Tap \"Share the update link\" → send it to the Aolithium Live " +
                    "chat: it will be built into the next version, and other phones will not need to paste anything.",
            ), 13.5f, Palette.accent))
        }
        val ready = apkReady && jsonReady && apkLink
        val publish = ui.button(tr("Опублікувати зараз", "Publish now"), Ui.Kind.PRIMARY, large = true) { publish(quiet = false) }
        ui.setEnabled(publish, ready)
        box.addView(publish, ui.matchWrap())
        statusLine(13.5f)?.let { box.addView(it) }

        // T37: «Публікувати автоматично» — лише телефон автора, за замовчуванням вимкнено.
        box.addView(ui.text(tr("Публікувати автоматично", "Publish automatically"), 15.5f, Palette.text, medium = true))
        val autoOn = prefs.getBoolean(KEY_AUTO_PUBLISH, false)
        box.addView(ui.grid(listOf(
            ui.choice(tr("Так", "Yes"), autoOn) { setAutoPublish(true) },
            ui.choice(tr("Ні", "No"), !autoOn) { setAutoPublish(false) },
        )), ui.matchWrap())
        box.addView(ui.muted(tr(
            "Так — коли відкриваєш застосунок, а встановлена версія новіша за опубліковану, телефон сам кладе її на Диск " +
                "і перевіряє через інтернет, що інші телефони її бачать. Тихо, без вікон; результат — у картці «Оновлення " +
                "застосунку» на головній. Працює після кроків 1–4.",
            "Yes — when you open the app and the installed version is newer than the published one, the phone puts it " +
                "on Drive by itself and checks over the internet that other phones see it. Quietly, without windows; the " +
                "result is in the \"App update\" card on the home screen. Works after steps 1–4.",
        ), 13f))

        if (jsonLink) {
            box.addView(ui.button(tr("Поділитися посиланням на оновлення", "Share the update link"), Ui.Kind.NORMAL) { shareLink(jsonId) }, ui.matchWrap())
        }
        if (apkReady || jsonReady || apkLink || jsonLink) {
            box.addView(ui.button(tr("Почати налаштування заново", "Start the setup again"), Ui.Kind.QUIET) { confirmReset() }, ui.matchWrap())
        }
        box.addView(Overlay.footer(ui.button(tr("Закрити", "Close"), Ui.Kind.QUIET) {
            host.overlay.dismiss()
            host.updateChanged()
        }), ui.matchWrap())
        host.overlay.show(KEY_DIALOG_PUBLISH, box, keepScroll = keepScroll)
    }

    private fun setAutoPublish(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PUBLISH, on).apply()
        // Увімкнули, а встановлена версія ще не опублікована — одразу, без чекання наступного відкриття.
        if (on) autoPublish()
        showPublishSheet(keepScroll = true)
        host.updateChanged()
    }

    /**
     * 0.4.6: файли на Диску створено заздалегідь (посилання вбудовані) — автор лише вибирає їх, з правом запису.
     * Новий файл не створюємо: інакше вийшов би дублікат «(1)», а вбудоване посилання дивилось би на старий.
     */
    private fun pickFile(request: Int) {
        val mime = if (request == REQ_APK) "*/*" else "application/json"
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        host.overlay.dismiss()
        host.toast(tr("☰ ліворуч → «Google Диск» → ${DriveUpdate.BUILT_IN_FOLDER}", "☰ on the left → \"Google Drive\" → ${DriveUpdate.BUILT_IN_FOLDER}"))
        try {
            @Suppress("DEPRECATION")
            host.startActivityForResult(i, request)
        } catch (e: ActivityNotFoundException) {
            host.toast(tr("На цьому телефоні немає вікна вибору файлу", "This phone has no file picker"))
        }
    }

    private fun createFile(request: Int, mime: String, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        host.overlay.dismiss()
        host.toast(tr("У вікні вибору натисни ☰ ліворуч → «Google Диск».", "In the picker tap ☰ on the left → \"Google Drive\"."))
        try {
            @Suppress("DEPRECATION")
            host.startActivityForResult(i, request)
        } catch (e: ActivityNotFoundException) {
            host.toast(tr("На цьому телефоні немає вікна створення файлу", "This phone has no file creation window"))
        }
    }

    /**
     * Після вікна вибору: тримаємо постійний дозвіл на файл і запам'ятовуємо його. Постійний — щоб і через тижні
     * (і для «Публікувати автоматично») телефон писав у ті самі файли без вікна вибору.
     */
    fun onFilePicked(request: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || data == null || uri == null) {
            showPublishSheet()
            return
        }
        try {
            var flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            host.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            showProblem(tr(
                "Android не дав постійного доступу до цього файлу. Створи файл саме через «Google Диск» у меню ☰ вікна вибору.",
                "Android did not give lasting access to this file. Create the file through \"Google Drive\" in the ☰ menu of the picker.",
            ), offerLink = false)
            return
        }
        val apk = request == REQ_APK
        val picked = DriveUpdate.filesBuiltIn
        var name = if (apk) DriveUpdate.APK_FILE_NAME else DriveUpdate.VERSION_FILE_NAME
        runCatching {
            host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        prefs.edit()
            .putString(if (apk) KEY_APK_URI else KEY_JSON_URI, uri.toString())
            .putString(if (apk) KEY_APK_NAME else KEY_JSON_NAME, name)
            .apply()
        val expected = if (apk) DriveUpdate.APK_FILE_NAME else DriveUpdate.VERSION_FILE_NAME
        if (picked && name != expected) {
            // 0.4.6: вибрали не той файл — не запам'ятовуємо, інакше публікація пішла б у чужий файл.
            prefs.edit().remove(if (apk) KEY_APK_URI else KEY_JSON_URI).remove(if (apk) KEY_APK_NAME else KEY_JSON_NAME).apply()
            showProblem(tr(
                "Вибрано «$name», а потрібен «$expected» з папки ${DriveUpdate.BUILT_IN_FOLDER}. Натисни крок ще раз і вибери саме його.",
                "You picked \"$name\", but \"$expected\" from the folder ${DriveUpdate.BUILT_IN_FOLDER} is needed. Tap the step again and pick exactly that file.",
            ), offerLink = false)
            return
        }
        if (!apk && !picked) {
            // Файл версії має мати вміст, щоб у Диску можна було скопіювати посилання.
            val resolver = host.contentResolver
            Thread({ runCatching { writeText(resolver, uri, DriveUpdate.PLACEHOLDER) } }, "update-json-placeholder").start()
        }
        showPublishSheet()
    }

    /** Посилання з буфера → ID файлу; немає — вікно з поясненням і null. */
    private fun linkFromClipboard(): String? {
        val text = runCatching {
            val clip = (host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(host)?.toString().orEmpty() else ""
        }.getOrDefault("")
        val id = DriveUpdate.fileIdFromLink(text)
        if (id == null) {
            val cut = if (text.length > 80) text.take(80) + "…" else text
            val shown = if (text.isBlank()) "" else tr("\n\nЗараз у буфері: «$cut»", "\n\nNow in the clipboard: \"$cut\"")
            host.toast(tr("У буфері немає посилання на файл Диска", "No Drive file link in the clipboard"))
            showProblem(tr(
                "У буфері немає посилання на файл Google Диска.\n\nВідкрий Google Диск → ⋮ біля файлу → «Надати доступ» → " +
                    "«Усі, хто має посилання» → «Копіювати посилання». Потім повернись сюди й натисни кнопку ще раз.$shown",
                "There is no Google Drive file link in the clipboard.\n\nOpen Google Drive → ⋮ next to the file → \"Share\" → " +
                    "\"Anyone with the link\" → \"Copy link\". Then come back here and tap the button again.$shown",
            ), offerLink = false)
        }
        return id
    }

    /** Кроки 3–4 автора. Те саме посилання в обох кроках — помилка (Track Catcher T46). */
    private fun pasteLink(key: String): Boolean {
        val id = linkFromClipboard() ?: return false
        val other = prefs.getString(if (key == KEY_APK_ID) KEY_JSON_ID else KEY_APK_ID, "").orEmpty()
        if (id == other) {
            val apkName = DriveUpdate.APK_FILE_NAME
            val jsonName = DriveUpdate.VERSION_FILE_NAME
            showProblem(
                (if (key == KEY_APK_ID) tr("Це те саме посилання, що вже вставлене в кроці 4.", "This is the same link already pasted in step 4.")
                else tr("Це те саме посилання, що вже вставлене в кроці 3.", "This is the same link already pasted in step 3.")) + "\n\n" +
                    tr("Крок 3 — посилання на «$apkName», крок 4 — на «$jsonName».", "Step 3 is the link to \"$apkName\", step 4 to \"$jsonName\"."),
                offerLink = false,
            )
            return false
        }
        val edit = prefs.edit().putString(key, id)
        if (key == KEY_JSON_ID) edit.putString(KEY_MANIFEST_ID, id)
        edit.apply()
        host.toast(tr("Посилання вставлено", "Link pasted"))
        return true
    }

    /** Посилання для перевірки: зберігається лише тоді, коли за ним справді файл версії (Track Catcher T46). */
    private fun checkPastedLink() {
        val id = linkFromClipboard() ?: return
        host.toast(tr("Посилання вставлено", "Link pasted"))
        check(quiet = false, pasted = id)
    }

    private fun shareLink(jsonId: String) {
        val url = DriveUpdate.viewUrl(jsonId)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,
            tr(
                "Оновлення Aolithium Live: $url\n" +
                    "Скопіюй це посилання, потім в Aolithium Live: головна, унизу «Оновлення застосунку» → " +
                    "«Перевірити оновлення» → «Вставити посилання» (або «Інше посилання на оновлення»).",
                "Aolithium Live update: $url\n" +
                    "Copy this link, then in Aolithium Live: home screen, \"App update\" at the bottom → " +
                    "\"Check for updates\" → \"Paste link\" (or \"Another update link\").",
            ),
        )
        try {
            host.startActivity(Intent.createChooser(send, tr("Поділитися посиланням", "Share the link")))
        } catch (e: ActivityNotFoundException) {
            host.toast(tr("Немає застосунку, щоб поділитися", "No app to share with"))
        }
    }

    private fun confirmReset() {
        val body = host.overlay.dialog(
            title = tr("Почати заново?", "Start again?"),
            message = tr(
                "Телефон забуде обидва файли й посилання. На Диску нічого не зміниться — старі файли можна вибрати знову або прибрати вручну в Google Диску.",
                "The phone will forget both files and links. Nothing changes on Drive: the old files can be picked again or removed by hand in Google Drive.",
            ),
            actions = listOf(tr("Почати заново", "Start again") to Ui.Kind.DANGER, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    listOf(KEY_APK_URI, KEY_JSON_URI).mapNotNull { prefs.getString(it, "")?.takeIf { s -> s.isNotBlank() } }.forEach { u ->
                        runCatching { host.contentResolver.releasePersistableUriPermission(Uri.parse(u), flags) }
                    }
                    val keepManifest = prefs.getString(KEY_MANIFEST_ID, "")
                    val keepAuto = prefs.getBoolean(KEY_AUTO, true)
                    val keepAutoPublish = prefs.getBoolean(KEY_AUTO_PUBLISH, false)
                    prefs.edit().clear().putString(KEY_MANIFEST_ID, keepManifest).putBoolean(KEY_AUTO, keepAuto)
                        .putBoolean(KEY_AUTO_PUBLISH, keepAutoPublish).apply()
                }
                showPublishSheet()
            },
        )
        host.overlay.show(KEY_DIALOG_RESET, body)
    }

    /**
     * T37: тиха автопублікація при відкритті. true — почалась (тоді тиха перевірка оновлень цього разу не потрібна).
     * Спершу дивиться, що зараз на Диску (як інші телефони): там уже ця чи новіша — лише запам'ятовує, нічого не пише.
     */
    private fun autoPublish(): Boolean {
        if (!UpdateFeature.VISIBLE || BuildConfig.DEBUG || Updater.busy) return false
        val due = DriveUpdate.autoPublishDue(
            on = prefs.getBoolean(KEY_AUTO_PUBLISH, false),
            ready = setupReady(),
            installedCode = installedCode,
            publishedCode = prefs.getLong(KEY_PUBLISHED_CODE, 0L),
            triedCode = prefs.getLong(KEY_TRIED_CODE, 0L),
            triedAt = prefs.getLong(KEY_TRIED_AT, 0L),
            now = System.currentTimeMillis(),
        )
        if (!due) return false
        return publish(quiet = true)
    }

    /** [quiet] — автопублікація: без вікна поступу, рядок стану — у картці. true — робота почалась. */
    private fun publish(quiet: Boolean): Boolean {
        if (Updater.busy) {
            if (!quiet) host.toast(tr("Уже працюю…", "Already working…"))
            return false
        }
        val apkUri = prefs.getString(KEY_APK_URI, "").orEmpty()
        val jsonUri = prefs.getString(KEY_JSON_URI, "").orEmpty()
        val apkId = apkIdNow()
        if (apkUri.isBlank() || jsonUri.isBlank() || apkId.isBlank()) {
            if (!quiet) showPublishSheet()
            return false
        }
        val info = host.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) {
            if (!quiet) showProblem(tr(
                "Ця версія встановлена кількома файлами (split APK) — її не передати одним APK. Встав версію з меню Aolithium Live і спробуй ще раз.",
                "This version is installed as several files (split APK), so it cannot be passed on as one APK. Install the version from the Aolithium Live menu and try again.",
            ), offerLink = false)
            return false
        }
        val source = File(info.sourceDir)
        val resolver = host.contentResolver
        val pkg = host.packageName
        val code = installedCode
        val name = BuildConfig.VERSION_NAME
        val manifestIdNow = jsonIdNow().ifBlank { manifestId().orEmpty() }
        val store = prefs
        if (quiet) {
            Updater.busy = true
            Updater.quiet = true
            Updater.progressText = tr("Автоматично: дивлюсь, що на Диску…", "Automatically: checking what is on Drive…")
            Updater.progressFinished = false
            store.edit().putLong(KEY_TRIED_CODE, code).putLong(KEY_TRIED_AT, System.currentTimeMillis()).apply()
            host.updateChanged()
        } else {
            startProgress(tr("Публікую версію на Диск", "Publishing the version to Drive"), tr("Копіюю APK на Диск…", "Copying the APK to Drive…"))
        }
        Thread({
            var ok = false
            var skipped = false
            val outcome = try {
                // Автопублікація: на Диску вже ця чи новіша (напр. з іншого телефона) — нічого не пишемо.
                val online = if (quiet && manifestIdNow.isNotBlank()) readOnline(manifestIdNow) else null
                if (online != null && online.packageName == pkg && online.versionCode >= code) {
                    skipped = true
                    ok = true
                    store.edit().putLong(KEY_PUBLISHED_CODE, maxOf(code, store.getLong(KEY_PUBLISHED_CODE, 0L))).apply()
                    tr("На Диску вже ${online.versionName} · збірка ${online.versionCode} — публікувати не треба.",
                        "Drive already has ${online.versionName} · build ${online.versionCode}, nothing to publish.")
                } else {
                    publishAndVerify(resolver, source, Uri.parse(apkUri), Uri.parse(jsonUri), apkId, pkg, code, name, manifestIdNow) { ok = it }
                }
            } catch (e: SecurityException) {
                tr("Доступ до файлу на Диску втрачено — «Почати налаштування заново».", "Access to the file on Drive is lost. Use \"Start the setup again\".")
            } catch (e: Exception) {
                tr("Не вдалося: ", "Failed: ") + (e.message ?: tr("невідома помилка", "unknown error"))
            }
            val shown = if (quiet && !skipped) tr("Автоматично. ", "Automatically. ") + outcome else outcome
            val edit = store.edit().putString(KEY_LAST_RESULT, shown).putBoolean(KEY_LAST_OK, ok)
            if (ok) edit.putLong(KEY_PUBLISHED_CODE, maxOf(code, store.getLong(KEY_PUBLISHED_CODE, 0L)))
            edit.apply()
            Updater.busy = false
            Updater.quiet = false
            Updater.setProgress(shown, finished = true)
            Updater.onMain { h ->
                if (!(quiet && skipped)) {
                    h.toast(when {
                        ok && quiet -> tr("Нову версію опубліковано на Диск автоматично й перевірено", "The new version was published to Drive automatically and checked")
                        ok -> tr("Опубліковано й перевірено на Диску", "Published and checked on Drive")
                        quiet -> tr("Автопублікація не вдалась — подробиці в «Оновлення застосунку»", "Automatic publishing failed. Details in \"App update\"")
                        else -> tr("Публікація не дійшла до Диска — подробиці в «Оновлення застосунку»", "The publication did not reach Drive. Details in \"App update\"")
                    })
                }
                h.updateChanged()
            }
        }, "update-publish").start()
        return true
    }

    /** Що зараз видно з інтернету; помилка зв'язку — виняток (автопублікація тоді відкладається), «не наш файл» — null. */
    private fun readOnline(manifestId: String): DriveUpdate.Manifest? = try {
        DriveUpdate.Manifest.parse(DriveHttp.readText(manifestId, 64 * 1024))
    } catch (e: IllegalArgumentException) {
        null // заглушка «ще не опубліковано» чи зіпсований файл — перепишемо
    }

    /** Записати APK і файл версії в ті самі файли на Диску, потім перевірити через інтернет (Track Catcher 0.1.48). */
    private fun publishAndVerify(
        resolver: android.content.ContentResolver,
        source: File,
        apkUri: Uri,
        jsonUri: Uri,
        apkId: String,
        pkg: String,
        code: Long,
        name: String,
        manifestId: String,
        result: (Boolean) -> Unit,
    ): String {
        val total = source.length()
        val sha = DriveUpdate.newSha256()
        var done = 0L
        val raw = runCatching { resolver.openOutputStream(apkUri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(apkUri, "w")
            ?: throw IOException(tr("файл APK на Диску не відкрився для запису", "the APK file on Drive did not open for writing"))
        raw.use { out ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(64 * 1024)
                var lastShown = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    sha.update(buffer, 0, n)
                    done += n
                    if (done - lastShown > 1024 * 1024 || done == total) {
                        lastShown = done
                        Updater.setProgress(tr("Копіюю APK на Диск… ", "Copying the APK to Drive… ") +
                            DriveUpdate.formatMb(done) + tr(" з ", " of ") + DriveUpdate.formatMb(total))
                    }
                }
            }
        }
        val manifest = DriveUpdate.Manifest(pkg, code, name, DriveUpdate.sha256Hex(sha.digest()), done, apkId, System.currentTimeMillis())
        Updater.setProgress(tr("Записую файл версії…", "Writing the version file…"))
        writeText(resolver, jsonUri, manifest.toJson())
        val time = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date())
        // Записали — ще не значить вивантажили: Google Диск робить це у фоні. Перевіряємо, як інші телефони.
        val seen = waitUntilPublished(manifest, manifestId)
        val ok = seen == null
        result(ok)
        val ver = "$name · " + tr("збірка", "build") + " $code (${DriveUpdate.formatMb(done)}) · $time"
        return if (ok) {
            tr("Опубліковано й перевірено $ver\nІнші телефони вже бачать цю версію: файл версії й APK з інтернету — саме ці.",
                "Published and checked $ver\nOther phones already see this version: the version file and APK from the internet are exactly these.")
        } else {
            tr(
                "НЕ ДІЙШЛО до Диска (записано на телефоні $time, але за 6 хв Google Диск не вивантажив).\n$seen\n\n" +
                    "Що зробити: відкрий застосунок Google Диск (там видно вивантаження), перевір інтернет, і натисни «Опублікувати зараз» ще раз.",
                "DID NOT REACH Drive (written on the phone at $time, but Google Drive did not upload it within 6 min).\n$seen\n\n" +
                    "What to do: open the Google Drive app (uploads are shown there), check the internet, and tap \"Publish now\" again.",
            )
        }
    }

    /**
     * Чекає, поки опублікована версія стане видна з інтернету (як її бачать інші телефони). null — так, видно;
     * інакше — що там зараз (для пояснення).
     */
    private fun waitUntilPublished(m: DriveUpdate.Manifest, manifestId: String): String? {
        if (manifestId.isBlank()) return tr("Нема посилання на файл версії — перевірити нема чим.", "No link to the version file, nothing to check with.")
        var last = tr("На Диску поки стара версія.", "Drive still has the old version.")
        val started = System.currentTimeMillis()
        for (delay in DriveUpdate.PUBLISH_CHECKS_MS) {
            val waited = (System.currentTimeMillis() - started) / 1000
            val clock = "${waited / 60}:" + "%02d".format(Locale.ROOT, waited % 60)
            Updater.setProgress(tr(
                "Записано. Чекаю, поки Google Диск вивантажить… ($clock)\nМожна сховати — перевірка йде далі.",
                "Written. Waiting for Google Drive to upload… ($clock)\nYou can hide this: the check continues.",
            ))
            Thread.sleep(delay)
            try {
                val online = DriveUpdate.Manifest.parse(DriveHttp.readText(manifestId, 64 * 1024))
                if (!DriveUpdate.manifestIsOurs(online, m.versionCode, m.apkSha256, m.apkSize)) {
                    last = tr("Файл версії на Диску: ${online.versionName} · збірка ${online.versionCode}.",
                        "Version file on Drive: ${online.versionName} · build ${online.versionCode}.")
                    continue
                }
                Updater.setProgress(tr("Файл версії вже на Диску. Звіряю APK…", "The version file is on Drive. Comparing the APK…"))
                val apk = DriveHttp.download(m.apkId, Discard, m.apkSize + 1, expectApk = true, progress = null)
                if (apk.size == m.apkSize && apk.sha256.equals(m.apkSha256, ignoreCase = true)) return null
                last = tr("Файл версії вже новий, а APK на Диску ще старий (${DriveUpdate.formatMb(apk.size)}).",
                    "The version file is new, but the APK on Drive is still old (${DriveUpdate.formatMb(apk.size)}).")
            } catch (e: Exception) {
                last = tr("Перевірка не вдалась: ", "The check failed: ") + (e.message ?: tr("немає зв'язку", "no connection"))
            }
        }
        return last
    }

    private object Discard : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    // ---------- кожен телефон: перевірити, завантажити, встановити ----------

    fun manifestId(): String? =
        prefs.getString(KEY_MANIFEST_ID, "")?.takeIf { it.isNotBlank() } ?: DriveUpdate.BUILT_IN_MANIFEST_ID.takeIf { it.isNotBlank() }

    /** При відкритті застосунку: не частіше раз на 24 год; мовчить, якщо нової версії нема. */
    private fun autoCheck() {
        val now = System.currentTimeMillis()
        if (!DriveUpdate.autoCheckDue(prefs.getBoolean(KEY_AUTO, true), manifestId() != null, prefs.getLong(KEY_LAST_AUTO, 0L), now)) return
        if (Updater.busy) return
        prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
        check(quiet = true)
    }

    /**
     * [pasted] — щойно вставлене посилання: перевіряється лише воно й зберігається, тільки якщо за ним
     * файл версії. Інакше — збережене, а якщо воно не на файл версії (напр. на APK, Track Catcher T46), воно
     * забувається й пробується вбудоване в застосунок.
     */
    fun check(quiet: Boolean, pasted: String? = null) {
        if (!UpdateFeature.VISIBLE) return
        val saved = prefs.getString(KEY_MANIFEST_ID, "")?.takeIf { it.isNotBlank() }
        val candidates = DriveUpdate.manifestCandidates(pasted, saved, DriveUpdate.BUILT_IN_MANIFEST_ID)
        if (candidates.isEmpty()) {
            if (!quiet) askForLink()
            return
        }
        if (Updater.busy) {
            if (!quiet) host.toast(tr("Уже працюю…", "Already working…"))
            return
        }
        Updater.busy = true
        if (!quiet && pasted == null) host.toast(tr("Перевіряю оновлення…", "Checking for updates…"))
        val pkg = host.packageName
        val code = installedCode
        val store = prefs
        Thread({
            // Пробуємо всі кандидати (збережене посилання і вбудоване) і беремо найновішу версію (Track Catcher T56):
            // збережене посилання на старий файл версії не має перекривати вбудоване. Збережене «не те» — забуваємо.
            var manifest: DriveUpdate.Manifest? = null
            var problem: String? = null
            val read = ArrayList<Pair<String, DriveUpdate.Manifest>>()
            for (id in candidates) {
                try {
                    read += id to DriveUpdate.Manifest.parse(DriveHttp.readText(id, 64 * 1024))
                } catch (e: DriveUpdate.WrongFileException) {
                    problem = e.message
                    if (pasted == null && id == saved) store.edit().remove(KEY_MANIFEST_ID).apply()
                } catch (e: IllegalArgumentException) {
                    problem = e.message
                } catch (e: Exception) {
                    problem = e.message ?: tr("Немає зв'язку з Google Диском.", "No connection to Google Drive.")
                }
            }
            DriveUpdate.newest(read)?.let { (id, m) ->
                manifest = m
                problem = null
                if (pasted != null) store.edit().putString(KEY_MANIFEST_ID, id).apply()
                else if (saved != null && id != saved) store.edit().remove(KEY_MANIFEST_ID).apply()
            }
            Updater.busy = false
            val found = manifest
            if (quiet) {
                if (found != null && DriveUpdate.compare(found, pkg, code) == DriveUpdate.Verdict.NEWER) {
                    Updater.onMain { UpdateUi(it).showVerdict(found) }
                }
                return@Thread
            }
            Updater.onMain { h ->
                h.updateChanged()
                if (found == null) UpdateUi(h).showProblem(problem ?: tr("Невідома помилка.", "Unknown error."), offerLink = true)
                else UpdateUi(h).showVerdict(found)
            }
        }, "update-check").start()
    }

    private fun askForLink() {
        val body = host.overlay.dialog(
            title = tr("Звідки брати оновлення?", "Where to get updates from?"),
            message = tr(
                "Потрібне посилання на файл оновлень на Google Диску автора. Скопіюй його (автор надсилає посилання) і натисни «Вставити посилання».",
                "A link to the update file on the author's Google Drive is needed. Copy it (the author sends the link) and tap \"Paste link\".",
            ),
            actions = listOf(tr("Вставити посилання", "Paste link") to Ui.Kind.PRIMARY, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0) checkPastedLink()
            },
        )
        host.overlay.show(KEY_DIALOG_LINK, body)
    }

    fun showProblem(message: String, offerLink: Boolean) {
        Updater.problem = message
        Updater.problemOffersLink = offerLink
        val actions = if (offerLink) listOf(tr("Інше посилання", "Another link") to Ui.Kind.NORMAL, tr("Закрити", "Close") to Ui.Kind.QUIET)
        else listOf(tr("Закрити", "Close") to Ui.Kind.QUIET)
        val body = host.overlay.dialog(
            title = tr("Оновлення: не вдалося", "Update: failed"),
            message = message + if (offerLink) tr("\n\nБез інтернету перевірка не працює.", "\n\nThe check does not work without the internet.") else "",
            actions = actions,
            onAction = { i ->
                host.overlay.dismiss()
                Updater.problem = null
                if (offerLink && i == 0) checkPastedLink()
            },
        )
        host.overlay.show(KEY_DIALOG_PROBLEM, body)
    }

    fun showVerdict(m: DriveUpdate.Manifest) {
        Updater.verdict = m
        val mine = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val theirs = "${m.versionName} (${m.versionCode})"
        val other = tr("Інше посилання", "Another link") to Ui.Kind.NORMAL
        val close = tr("Закрити", "Close") to Ui.Kind.QUIET
        val verdict = DriveUpdate.compare(m, host.packageName, installedCode)
        val (title, message, actions) = when (verdict) {
            DriveUpdate.Verdict.NEWER -> Triple(
                tr("Є нова версія", "A new version is available"),
                tr(
                    "На Диску: $theirs\nУ тебе: $mine\n\nРозмір: ${DriveUpdate.formatMb(m.apkSize)}. Застосунок завантажить файл, " +
                        "перевірить, що він справжній, і відкриє вікно Android «Оновити». Батареї, історія й налаштування лишаються.",
                    "On Drive: $theirs\nYou have: $mine\n\nSize: ${DriveUpdate.formatMb(m.apkSize)}. The app will download the file, " +
                        "check that it is genuine and open the Android \"Update\" window. Batteries, history and settings stay.",
                ),
                listOf(tr("Завантажити й встановити", "Download and install") to Ui.Kind.PRIMARY, tr("Не зараз", "Not now") to Ui.Kind.QUIET),
            )
            DriveUpdate.Verdict.SAME -> Triple(
                tr("У тебе остання версія", "You have the latest version"),
                tr("Встановлено $mine — така сама, як на Диску.", "Installed $mine, the same as on Drive.") + otherLinkHint(),
                listOf(other, close),
            )
            DriveUpdate.Verdict.OLDER -> Triple(
                tr("У тебе новіша версія", "You have a newer version"),
                tr("Встановлено $mine, на Диску старіша: $theirs.", "Installed $mine, Drive has an older one: $theirs.") + otherLinkHint(),
                listOf(other, close),
            )
            DriveUpdate.Verdict.OTHER_PACKAGE -> Triple(
                tr("Інший варіант застосунку", "Another variant of the app"),
                tr("На Диску версія для ${m.packageName}, а в тебе ${host.packageName}.", "Drive has a version for ${m.packageName}, and you have ${host.packageName}."),
                listOf(other, close),
            )
        }
        val newer = verdict == DriveUpdate.Verdict.NEWER
        val body = host.overlay.dialog(title, message, actions, onAction = { i ->
            host.overlay.dismiss()
            Updater.verdict = null
            // Track Catcher T54: «Інше посилання» — і коли перевірка вдала (раніше лише після помилки — старий файл версії був тупиком).
            if (newer && i == 0) download(m) else if (!newer && i == 0) checkPastedLink()
        })
        host.overlay.show(KEY_DIALOG_VERDICT, body)
    }

    private fun download(m: DriveUpdate.Manifest) {
        if (Updater.busy) return
        val file = UpdateApkProvider.apkFile(host)
        val dir = file.parentFile ?: return
        val pm = host.packageManager
        val installedCert = installedCertSha256()
        startProgress(tr("Завантажую оновлення", "Downloading the update"), tr("Починаю…", "Starting…"))
        Thread({
            val problem: String? = try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val result = FileOutputStream(file).use { out ->
                    DriveHttp.download(m.apkId, out, MAX_APK_BYTES, expectApk = true) { done, total ->
                        Updater.setProgress(tr("Завантажено ", "Downloaded ") + DriveUpdate.formatMb(done) + tr(" з ", " of ") +
                            DriveUpdate.formatMb(if (total > 0) total else m.apkSize))
                    }
                }
                Updater.setProgress(tr("Перевіряю файл…", "Checking the file…"))
                val archive = archiveInfo(pm, file)
                DriveUpdate.checkDownloaded(
                    m, result.sha256, result.size,
                    archive?.packageName, archive?.let { versionCodeOf(it) } ?: -1L,
                    archive?.let { certSha256(it) }, installedCert,
                )
            } catch (e: Exception) {
                e.message ?: tr("Завантаження обірвалось.", "The download broke off.")
            }
            Updater.busy = false
            if (problem != null) {
                file.delete()
                Updater.setProgress(problem, finished = true)
                Updater.onMain { h -> if (h.overlay.key != KEY_DIALOG_PROGRESS) UpdateUi(h).showProblem(problem, offerLink = false) }
            } else {
                Updater.setProgress(tr(
                    "Файл перевірено: розмір, контрольна сума, версія й ключ підпису збіглися.",
                    "File checked: size, checksum, version and signing key match.",
                ), finished = true)
                Updater.pendingApk = file
                Updater.onMain { h ->
                    if (h.overlay.key == KEY_DIALOG_PROGRESS) h.overlay.dismiss()
                    UpdateUi(h).install(file)
                }
            }
        }, "update-download").start()
    }

    fun install(file: File) {
        if (!file.isFile) {
            Updater.pendingApk = null
            return
        }
        // canRequestPackageInstalls — з Android 8 (API 26), тобто є на всіх телефонах, де працює застосунок.
        if (!host.packageManager.canRequestPackageInstalls()) {
            Updater.pendingApk = file
            val body = host.overlay.dialog(
                title = tr("Один дозвіл — один раз", "One permission, once"),
                message = tr(
                    "Файл перевірено. Щоб Android дозволив Aolithium Live встановити оновлення, на наступному екрані увімкни " +
                        "«Дозволити з цього джерела» й натисни «Назад» — встановлення почнеться саме.",
                    "The file is checked. For Android to let Aolithium Live install the update, on the next screen turn on " +
                        "\"Allow from this source\" and press \"Back\": the installation starts by itself.",
                ),
                actions = listOf(tr("Відкрити дозвіл", "Open the permission") to Ui.Kind.PRIMARY, tr("Скасувати", "Cancel") to Ui.Kind.QUIET),
                onAction = { i ->
                    host.overlay.dismiss()
                    if (i == 0) openInstallPermission() else Updater.pendingApk = null
                },
            )
            host.overlay.show(KEY_DIALOG_PERMISSION, body)
            return
        }
        Updater.pendingApk = null
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(UpdateApkProvider.uri(host), UpdateApkProvider.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            host.startActivity(i)
        } catch (e: Exception) {
            showProblem(tr("Не вдалося відкрити встановлення: ", "Could not open the installation: ") +
                (e.message ?: tr("невідома помилка", "unknown error")), offerLink = false)
        }
    }

    private fun openInstallPermission() {
        try {
            host.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + host.packageName)))
        } catch (e: Exception) {
            runCatching {
                host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + host.packageName)))
            }
        }
    }

    /** Повернулись з «Дозволити з цього джерела» — встановлення продовжується саме. */
    fun resumePendingInstall() {
        if (!UpdateFeature.VISIBLE) return
        val file = Updater.pendingApk ?: return
        if (host.overlay.key == KEY_DIALOG_PERMISSION) return // вікно ще відкрите — чекаємо «Відкрити дозвіл»
        if (!file.isFile) {
            Updater.pendingApk = null
            return
        }
        if (host.packageManager.canRequestPackageInstalls()) install(file)
    }

    private fun startProgress(title: String, text: String) {
        Updater.busy = true
        Updater.quiet = false
        Updater.progressTitle = title
        Updater.progressText = text
        Updater.progressFinished = false
        showProgress()
    }

    private fun showProgress() {
        val box = ui.column(14)
        box.addView(Overlay.header(ui.title(Updater.progressTitle, 19f)))
        // Track Catcher (faric 18.10 05:10, відео): кнопка стрибала вниз, коли замість «Копіюю APK…» з'являвся підсумок
        // на 5 рядків. Місце під текст — одразу на весь підсумок: вікно не міняє висоти, кнопка стоїть на місці.
        val status = ui.text(Updater.progressText, 15f, Palette.muted).apply { minLines = PROGRESS_LINES }
        box.addView(status)
        val close = ui.button(
            if (Updater.progressFinished) tr("Закрити", "Close") else tr("Сховати (робота йде далі)", "Hide (work continues)"),
            Ui.Kind.QUIET,
        ) {
            Updater.progressView = null
            Updater.progressButton = null
            host.overlay.dismiss()
            host.updateChanged()
        }
        // «Закрити» — у правий кут вікна, ближче до правої руки (Track Catcher T53).
        box.addView(Overlay.footer(close), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        Updater.progressView = status
        Updater.progressButton = close
        host.overlay.show(KEY_DIALOG_PROGRESS, box, dismissOnOutside = false)
    }

    /** Відновити вікно після перестворення (зміна мови). */
    fun restore(key: String): Boolean {
        if (!UpdateFeature.VISIBLE) return false
        when (key) {
            KEY_DIALOG_PUBLISH -> showPublishSheet()
            KEY_DIALOG_RESET -> confirmReset()
            KEY_DIALOG_LINK -> askForLink()
            KEY_DIALOG_PROGRESS -> showProgress()
            KEY_DIALOG_VERDICT -> Updater.verdict?.let { showVerdict(it) } ?: return false
            KEY_DIALOG_PROBLEM -> Updater.problem?.let { showProblem(it, Updater.problemOffersLink) } ?: return false
            KEY_DIALOG_PERMISSION -> Updater.pendingApk?.let { install(it) } ?: return false
            else -> return false
        }
        return true
    }

    private fun installedCertSha256(): String? = runCatching {
        val pm = host.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(host.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(host.packageName, PackageManager.GET_SIGNATURES)
        }
        certSha256(info)
    }.getOrNull()

    companion object {
        /** Підказка у вікні «остання / новіша версія» (Track Catcher T54). */
        private fun otherLinkHint(): String = tr(
            "\n\nАвтор казав, що є новіша? Скопіюй посилання від нього й натисни «Інше посилання».",
            "\n\nThe author said there is a newer one? Copy the link from them and tap \"Another link\".",
        )

        /** Не 40 — той уже в MainActivity (зберегти CSV). */
        const val REQ_APK = 60
        const val REQ_JSON = 61
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        private const val KEY_APK_URI = "apk_uri"
        private const val KEY_APK_NAME = "apk_name"
        private const val KEY_JSON_URI = "json_uri"
        private const val KEY_JSON_NAME = "json_name"
        private const val KEY_APK_ID = "apk_id"
        private const val KEY_JSON_ID = "json_id"
        private const val KEY_MANIFEST_ID = "manifest_id"
        private const val KEY_LAST_RESULT = "last_publish_result"
        private const val KEY_LAST_OK = "last_publish_ok"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST_AUTO = "last_auto_check"
        /** T37: автопублікація (автор) — увімкнена; яку версію вже опубліковано й перевірено; остання спроба. */
        private const val KEY_AUTO_PUBLISH = "auto_publish"
        private const val KEY_PUBLISHED_CODE = "published_code"
        private const val KEY_TRIED_CODE = "auto_tried_code"
        private const val KEY_TRIED_AT = "auto_tried_at"

        /** Скільки рядків тримати під текст поступу (найдовший підсумок на вузькому екрані — 5–6). */
        const val PROGRESS_LINES = 6

        const val KEY_DIALOG_PUBLISH = "update:publish"
        const val KEY_DIALOG_RESET = "update:reset"
        const val KEY_DIALOG_LINK = "update:link"
        const val KEY_DIALOG_PROGRESS = "update:progress"
        const val KEY_DIALOG_VERDICT = "update:verdict"
        const val KEY_DIALOG_PROBLEM = "update:problem"
        const val KEY_DIALOG_PERMISSION = "update:permission"

        fun writeText(resolver: android.content.ContentResolver, uri: Uri, text: String) {
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException(tr("файл версії на Диску не відкрився для запису", "the version file on Drive did not open for writing"))
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }

        /** Android 9+ (API 28) — новий спосіб читати ключ підпису; Android 8 — старий (`signatures`). */
        private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull()

        private fun versionCodeOf(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

        private fun certSha256(info: PackageInfo): String? = runCatching {
            val signers = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signers.isNullOrEmpty()) null
            else DriveUpdate.sha256Hex(DriveUpdate.newSha256().digest(signers[0].toByteArray()))
        }.getOrNull()
    }
}

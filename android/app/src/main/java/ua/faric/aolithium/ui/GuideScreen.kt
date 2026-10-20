package ua.faric.aolithium.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ua.faric.aolithium.core.DemoFrames
import ua.faric.aolithium.core.Lang
import ua.faric.aolithium.core.tr
import java.io.IOException

/**
 * Інструкція (T7, faric 20.10 05:22): сторінка з `assets/guide/index.html` — схеми, знімки екранів, обидві мови.
 * Джерело — `docs/guide/guide.html` (збирає `scripts/build_guide.py`, тест `tests/test_guide.py`). Замінила стару
 * «Довідку» (`HelpScreen` з текстом у коді): текст був у трьох місцях — тепер в одному.
 *
 * Як влаштовано (без бібліотек, як і весь застосунок):
 * - сторінку віддаємо самі з assets за адресою https://appassets.androidplatform.net/assets/… — з file:// у Track
 *   Catcher WebView не показував картинки; androidx WebViewAssetLoader тут нема, тож [serve] — свій, на 20 рядків;
 * - мова — **завжди мова застосунку** (T42, faric 20.10 17:02: «локалізація пливе» — інструкція англійською, а демо
 *   українською): `?lang=` при відкритті й `guideSetLang` після кожного завантаження сторінки (і після «Назад» з демо).
 *   «UA / EN» у верхньому рядку перемикають мову всього застосунку (`MainActivity.setLanguage`); `&app=1` — кнопки демо,
 *   сховати «UA / EN» на сторінці;
 * - місце на сторінці (T36) пам'ятає сама сторінка: кожен розділ і зміст — своє (localStorage), тож після демо чи
 *   «Назад» інструкція там, де була; `fresh=1` — забути місця;
 * - кнопки демо — посилання `app://demo/IDLE` → [MainActivity.openBattery] («Назад» з демо → сюди ж, T26);
 * - «Назад»: спершу історія сторінки (розділ → зміст), далі — вихід (`MainActivity.handleBack`);
 * - історія сторінки — у Bundle (`WebView.saveState`), тож «Назад» усередині працює й після демо.
 * Нема WebView на телефоні (рідко, Android System WebView вимкнено) — [fallback]: коротко й кнопки демо.
 * Альбомна (планшети, ui.twoPane) — сама сторінка верстається у дві колонки (CSS), запасний вигляд — кнопки по дві.
 */
class GuideScreen(host: MainActivity, state: Bundle?, section: String?) : Screen(host) {
    override val view: View
    private var web: WebView? = null

    init {
        val w = runCatching { WebView(host) }.getOrNull()
        web = w
        view = if (w == null) fallback() else {
            setUp(w)
            val saved = state?.getBundle(KEY_WEB)
            if (saved == null || w.restoreState(saved) == null) w.loadUrl(startUrl(section))
            w
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(w: WebView) {
        w.setBackgroundColor(Palette.bg)
        w.isVerticalScrollBarEnabled = true
        with(w.settings) {
            javaScriptEnabled = true // лише перемикання розділів і мови в локальній сторінці
            domStorageEnabled = true // мова, вибрана на самій сторінці
            allowFileAccess = false
            allowContentAccess = false
            // Шрифт телефона (faric тестує і на 1.3): WebView сам його не враховує.
            textZoom = (host.resources.configuration.fontScale * 100).toInt().coerceIn(80, 200)
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url)

            // T42: сторінка могла запам'ятати іншу мову (вибір на сторінці, стара версія) — завжди мова застосунку.
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("window.guideSetLang && window.guideSetLang('${Lang.current.tag}')", null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                if (url.scheme == "app" && url.host == "demo") {
                    val scene = url.lastPathSegment?.let { s -> DemoFrames.Scene.entries.firstOrNull { it.name == s } }
                    if (scene != null) host.openBattery("demo:" + scene.name)
                    return true
                }
                // Зовнішні посилання (GitHub) — у браузері; сам застосунок ходить в інтернет лише по оновлення (T37).
                try {
                    host.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: ActivityNotFoundException) {
                    host.toast(url.toString())
                }
                return true
            }
        }
    }

    /** Файл з assets за адресою https://[HOST]/assets/…; решта — не наша (null — WebView вирішує сам). */
    private fun serve(url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null
        val path = url.path?.removePrefix("/assets/")?.takeIf { it != url.path && !it.contains("..") } ?: return null
        val mime = when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "webp" -> "image/webp"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "css" -> "text/css"
            "js" -> "text/javascript"
            else -> "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null, host.assets.open(path))
        } catch (e: IOException) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), "".byteInputStream())
        }
    }

    private fun startUrl(section: String?): String {
        val base = "https://$HOST/assets/guide/index.html?lang=${Lang.current.tag}&app=1&fresh=1"
        return if (section.isNullOrBlank()) base else "$base#$section"
    }


    /** «Назад»: у сторінці є куди повертатись (розділ → зміст) — туди; інакше виходимо з інструкції. */
    override fun onBack(): Boolean {
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    override fun save(out: Bundle) {
        val w = web ?: return
        val b = Bundle()
        w.saveState(b)
        out.putBundle(KEY_WEB, b)
    }

    override fun onStop() {
        web?.onPause()
    }

    override fun onResume() {
        web?.onResume()
    }

    /** Нема WebView: що сталось і кнопки демо (те, що з довідки найпотрібніше без інструкції). */
    private fun fallback(): View {
        val col = ui.column(14)
        val card = ui.card()
        card.addView(ui.label(tr("Інструкція", "Guide"), LineIcon.HELP))
        card.addView(ui.text(tr(
            "Інструкція не відкрилась: на телефоні вимкнено або не встановлено «Android System WebView». Увімкни чи онови його в Google Play — і відкрий ще раз.",
            "The guide did not open: \"Android System WebView\" is turned off or missing on this phone. Turn it on or update it in Google Play, then open again.",
        ), 15f, Palette.text))
        col.addView(card, ui.matchWrap())
        val demo = ui.card()
        demo.addView(ui.label(tr("Демо без батареї", "Demo (no battery)"), LineIcon.INFO))
        demo.addView(ui.grid(DemoFrames.Scene.entries.map { scene ->
            ui.button(scene.title, Ui.Kind.NORMAL) { host.openBattery("demo:" + scene.name) }
        }, perRow = if (ui.twoPane) 2 else 1), ui.matchWrap())
        col.addView(demo, ui.matchWrap())
        col.addView(ui.muted("Aolithium Live · " + host.versionLine, 12f).apply { gravity = Gravity.CENTER }, ui.matchWrap())
        return ui.scroll(col)
    }

    private companion object {
        /** Адреса, яку Android відвів саме для сторінок з assets застосунку (у мережу запит не йде). */
        const val HOST = "appassets.androidplatform.net"
        const val KEY_WEB = "web"
    }
}

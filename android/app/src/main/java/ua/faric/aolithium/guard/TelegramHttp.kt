package ua.faric.aolithium.guard

import ua.faric.aolithium.core.Telegram
import ua.faric.aolithium.core.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * T48: єдине місце, що ходить у Telegram (`api.telegram.org`) — лише коли власник сам увімкнув сторожа й вставив
 * токен свого бота. Що слати — вирішує `core/Guard` і `core/GuardBot`; сюди приходить готовий текст.
 * Контракт `test_internet_only_for_drive_update_and_guard`: мережа в коді — лише тут і в `update/DriveHttp.kt`.
 */
object TelegramHttp {
    private const val MAX_BYTES = 1_000_000

    /** Виклик методу Bot API з тілом JSON. [readTimeoutMs] — для довгого опитування більше за його тайм-аут. */
    fun call(token: String, method: String, body: String, readTimeoutMs: Int = 20_000): Telegram.Reply {
        val c = try {
            URL(Telegram.url(token, method)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Telegram.Reply(false, null, 0, tr("нема зв'язку з Telegram", "no connection to Telegram"))
        }
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", "AolithiumLive-Guard/1")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.let { read(it) }
            val r = Telegram.reply(text)
            if (!r.ok && r.code == 0 && code !in 200..299) Telegram.Reply(false, null, code, "HTTP $code") else r
        } catch (e: IOException) {
            Telegram.Reply(false, null, 0, tr("нема зв'язку з Telegram", "no connection to Telegram"))
        } finally {
            c.disconnect()
        }
    }

    private fun read(input: InputStream): String {
        input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) break
            }
            return out.toString("UTF-8")
        }
    }
}

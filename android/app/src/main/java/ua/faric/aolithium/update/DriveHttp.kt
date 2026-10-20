package ua.faric.aolithium.update

import ua.faric.aolithium.core.DriveUpdate
import ua.faric.aolithium.core.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Читає відкритий («Усі, хто має посилання») файл Google Диска без входу в Google (T37, копія з Track Catcher).
 * Закритий чи видалений файл Диск віддає HTML-сторінкою — перші байти перевіряються,
 * і на екран іде зрозуміле речення, а не сміття. Лише читання з Диска автора: нічого про батарею сюди не йде.
 */
object DriveHttp {
    class Result(val size: Long, val sha256: String)

    private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000)

    val NOT_PUBLIC: String
        get() = tr(
            "Диск не віддав файл: він не відкритий для всіх, хто має посилання, видалений, " +
                "або посилання не те. Автору: у Google Диску → файл → «Надати доступ» → «Усі, хто має посилання».",
            "Drive did not give the file: it is not open to anyone with the link, it was deleted, " +
                "or the link is wrong. For the author: Google Drive → file → \"Share\" → \"Anyone with the link\".",
        )

    fun readText(fileId: String, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        download(fileId, out, maxBytes.toLong(), expectApk = false, progress = null)
        return out.toString("UTF-8")
    }

    /** Пише файл у [out]. З [expectApk] перші байти мають бути ZIP («PK»). */
    fun download(
        fileId: String,
        out: OutputStream,
        maxBytes: Long,
        expectApk: Boolean,
        progress: ((done: Long, total: Long) -> Unit)?,
    ): Result {
        // Диск буває відповідає 500 / 503 на мить (Track Catcher, faric 20.10 00:59 — саме тоді, коли автор заливав
        // нову версію): пробуємо ще двічі, через 2 і 5 с, — до того, як щось записано.
        var attempt = 0
        var c: HttpURLConnection
        var code: Int
        while (true) {
            c = URL(DriveUpdate.downloadUrl(fileId)).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "AolithiumLive-Updater/1")
            c.setRequestProperty("Cache-Control", "no-cache")
            code = runCatching { c.responseCode }.getOrElse { e -> c.disconnect(); throw e }
            if (!DriveUpdate.retryable(code) || attempt >= RETRY_DELAYS_MS.size) break
            c.disconnect()
            Thread.sleep(RETRY_DELAYS_MS[attempt++])
        }
        try {
            if (code == 401 || code == 403 || code == 404) throw IOException(NOT_PUBLIC)
            if (DriveUpdate.retryable(code)) {
                val tries = attempt + 1
                throw IOException(tr(
                    "Google Диск зараз не відповідає (код $code; спробував $tries рази). Спробуй за хвилину — це на боці Google.",
                    "Google Drive is not answering now (code $code; tried $tries times). Try again in a minute: it is on Google's side.",
                ))
            }
            if (code !in 200..299) throw IOException(tr("Google Диск відповів кодом $code. Спробуй пізніше.", "Google Drive answered with code $code. Try later."))
            val total = c.contentLengthLong
            if (total > maxBytes) {
                if (!expectApk) throw DriveUpdate.WrongFileException(DriveUpdate.linkIsApkMessage(total))
                throw IOException(tr("Файл на Диску завеликий (${DriveUpdate.formatMb(total)}).", "The file on Drive is too big (${DriveUpdate.formatMb(total)})."))
            }
            val sha = DriveUpdate.newSha256()
            return c.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var checkedHead = false
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    if (!checkedHead) {
                        checkedHead = true
                        if (DriveUpdate.looksLikeHtml(c.contentType, buffer, n)) throw IOException(NOT_PUBLIC)
                        if (expectApk && !DriveUpdate.looksLikeApk(buffer, n)) {
                            throw IOException(tr("На Диску за цим посиланням не APK-файл.", "This Drive link does not lead to an APK file."))
                        }
                        if (!expectApk && DriveUpdate.looksLikeApk(buffer, n)) throw DriveUpdate.WrongFileException(DriveUpdate.linkIsApkMessage(total))
                    }
                    done += n
                    if (done > maxBytes) {
                        if (!expectApk) throw DriveUpdate.WrongFileException(DriveUpdate.linkIsApkMessage(-1))
                        throw IOException(tr("Файл на Диску завеликий.", "The file on Drive is too big."))
                    }
                    sha.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    if (progress != null && done - lastReport >= 256 * 1024) {
                        lastReport = done
                        progress(done, total)
                    }
                }
                if (!checkedHead) throw IOException(tr("Файл на Диску порожній.", "The file on Drive is empty."))
                progress?.invoke(done, total)
                Result(done, DriveUpdate.sha256Hex(sha.digest()))
            }
        } finally {
            c.disconnect()
        }
    }
}

package ua.faric.aolithium.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * T37: віддає вікну встановлення Android лише один файл — перевірений APK оновлення (`cache/updates/…`).
 * У Track Catcher це робить `androidx.core.content.FileProvider`; тут бібліотек нема (правило проєкту) —
 * свій найпростіший провайдер: лише читання, лише цей файл, не експортований (доступ — тимчасовим дозволом
 * `FLAG_GRANT_READ_URI_PERMISSION` на одне відкриття).
 */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        if (uri.pathSegments != listOf(SEGMENT, FILE_NAME)) return null
        return apkFile(ctx).takeIf { it.isFile }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val f = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = MIME

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(cols.toTypedArray(), 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val MIME = "application/vnd.android.package-archive"
        const val DIR = "updates"
        const val FILE_NAME = "aolithium-live-update.apk"
        private const val SEGMENT = "apk"

        /** Той самий `authorities`, що в маніфесті (`${applicationId}.updates`; debug — з `.dev`). */
        fun authority(ctx: Context): String = ctx.packageName + ".updates"

        fun apkFile(ctx: Context): File = File(File(ctx.cacheDir, DIR), FILE_NAME)

        fun uri(ctx: Context): Uri = Uri.Builder().scheme("content").authority(authority(ctx))
            .appendPath(SEGMENT).appendPath(FILE_NAME).build()
    }
}

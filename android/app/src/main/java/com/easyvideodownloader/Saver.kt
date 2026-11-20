package com.easyvideodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Moves finished downloads into Downloads/Video Downloads, where every file manager can see them. */
object Saver {
    const val FOLDER = "Video Downloads"

    private val MIME = mapOf(
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "opus" to "audio/ogg",
        "ogg" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
    )

    fun mimeFor(name: String): String =
        MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** Copies [file] into the public Downloads folder and returns a link to open it with. */
    fun save(context: Context, file: File): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(context, file)
        else saveLegacy(context, file)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android could not create the file in Downloads.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Android could not open the new file.")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Permission denied: could not create $dir")
        val dest = uniqueFile(dir, file.name)
        file.copyTo(dest)
        // Ask the media scanner to index the file; it also gives back a link that other apps can open.
        var result: Uri? = null
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mimeFor(dest.name))) { _, uri ->
            result = uri
            done.countDown()
        }
        done.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }
}

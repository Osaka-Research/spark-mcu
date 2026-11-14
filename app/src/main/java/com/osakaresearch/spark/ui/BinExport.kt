package com.osakaresearch.spark.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a built firmware image to the public Downloads directory so it
 * survives the next build (which wipes [Context.getFilesDir]/build/sketch)
 * and shows up in the system picker and any file manager.
 *
 * Mirrors [LogExport]'s API shape and SDK split — MediaStore on Q+, legacy
 * [Environment.getExternalStoragePublicDirectory] below that.
 */
data class ExportedBin(val uri: Uri, val displayName: String, val humanPath: String)

object BinExport {
    fun save(context: Context, prefix: String, bytes: ByteArray): ExportedBin? =
        saveAs(context, prefix, "bin", bytes)

    /**
     * Writes any blob to Downloads/spark-<prefix>-<stamp>.<ext>. Used by
     * SketchBuilder clients to park the sketch.elf next to the firmware.bin
     * so crash-PC lookups can be done from a shell after the fact.
     */
    fun saveAs(context: Context, prefix: String, ext: String, bytes: ByteArray): ExportedBin? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "spark-$prefix-$stamp.$ext"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, name, bytes)
        } else {
            saveLegacy(context, name, bytes)
        }
    }

    private fun saveViaMediaStore(context: Context, name: String, bytes: ByteArray): ExportedBin? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run { resolver.delete(uri, null, null); return null }
            ExportedBin(uri, name, "Downloads/$name")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveLegacy(context: Context, name: String, bytes: ByteArray): ExportedBin? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return try {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            ExportedBin(Uri.fromFile(f), name, "Downloads/$name")
        } catch (t: Throwable) {
            null
        }
    }
}

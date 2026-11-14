package com.osakaresearch.spark.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExport {
    fun save(context: Context, prefix: String, content: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "spark-$prefix-$stamp.log"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, name, content)
        } else {
            saveLegacy(name, content)
        }
    }

    private fun saveViaMediaStore(context: Context, name: String, content: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            "Downloads/$name"
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveLegacy(name: String, content: String): String? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return try {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeText(content)
            "Downloads/$name"
        } catch (t: Throwable) {
            null
        }
    }
}

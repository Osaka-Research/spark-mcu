package com.osakaresearch.spark.net

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Builds a flash plan from a user-picked folder of precompiled blobs.
 *
 * A user-picked Blink folder supplies a four-image flash plan. APKs that
 * bundle the optional S3 compiler also carry the generic S3 board images;
 * [loadS3Bundled] combines them with a newly compiled app image.
 */
object PrecompiledBlobs {

    /**
     * Expected filenames + flash offsets for an ESP32-S3 (arduino-esp32 3.x)
     * blink. These are the stock arduino-cli outputs; the app binary is the
     * one the user renames from `<sketch>.ino.bin` to `firmware.bin`.
     *
     * Offsets reflect the S3 flash layout (bootloader at 0x0 — not 0x1000 as
     * on original ESP32). boot_app0.bin lives at 0xe000 and marks OTA slot 0
     * as the active partition.
     */
    private val S3_BLINK_LAYOUT = listOf(
        "bootloader.bin" to 0x0,
        "partitions.bin" to 0x8000,
        "boot_app0.bin"  to 0xe000,
        "firmware.bin"   to 0x10000,
    )

    sealed class Loaded {
        data class Ok(val plan: List<FlashBlob>) : Loaded()
        data class Missing(val names: List<String>) : Loaded()
        data class Error(val reason: String) : Loaded()
    }

    /** Use the board images packaged with the S3 core for a freshly built app. */
    fun loadS3Bundled(context: Context, appUri: Uri, coreDir: File): Loaded {
        val prebuilt = File(coreDir, "prebuilt")
        val found = mutableListOf<FlashBlob>()
        for ((name, offset) in S3_BLINK_LAYOUT) {
            val bytes = try {
                if (name == "firmware.bin") {
                    context.contentResolver.openInputStream(appUri)?.use { it.readBytes() }
                } else {
                    File(prebuilt, name).takeIf { it.isFile }?.readBytes()
                }
            } catch (t: Throwable) {
                return Loaded.Error("$name: ${t.message ?: "read failed"}")
            } ?: return Loaded.Missing(listOf(name))
            if (bytes.isEmpty()) return Loaded.Error("$name: empty file")
            found.add(FlashBlob(name, offset, bytes))
        }
        return Loaded.Ok(found)
    }

    fun loadS3Blink(context: Context, folderUri: Uri): Loaded {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: return Loaded.Error("folder not readable")

        // Enumerate once rather than calling findFile per name — findFile goes
        // through a DISPLAY_NAME query that can miss files written from outside
        // the DocumentsProvider (e.g. Termux `gh run download` into /sdcard),
        // while listFiles() walks the tree's child-documents URI directly.
        // Match case-insensitively for the same reason.
        val byName = tree.listFiles()
            .filter { it.isFile }
            .associateBy { (it.name ?: "").lowercase() }

        val found = mutableListOf<FlashBlob>()
        val missing = mutableListOf<String>()
        for ((name, offset) in S3_BLINK_LAYOUT) {
            val doc = byName[name.lowercase()]
            if (doc == null) {
                missing.add(name)
                continue
            }
            val bytes = try {
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                return Loaded.Error("$name: ${t.message ?: "read failed"}")
            } ?: return Loaded.Error("$name: cannot open")
            if (bytes.isEmpty()) return Loaded.Error("$name: empty file")
            found.add(FlashBlob(name, offset, bytes))
        }
        if (missing.isNotEmpty()) return Loaded.Missing(missing)
        return Loaded.Ok(found)
    }
}

package com.osakaresearch.spark.compile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Stable target-bound builds shared by Firmware and AI, including after app restart. */
object BuildArtifacts {
    data class Artifact(val id: String, val target: McuTarget, val bin: File, val metadata: JSONObject)
    private fun root(context: Context) = File(context.filesDir, "assistant/builds").apply { mkdirs() }

    @Synchronized fun save(context: Context, result: SketchBuilder.Result.Ok, target: McuTarget, source: File): Artifact {
        val sha = MessageDigest.getInstance("SHA-256").digest(result.output.readBytes())
            .joinToString("") { "%02x".format(it) }
        val id = "${target.id}-$sha"
        val dir = root(context)
        val bin = File(dir, "$id.bin")
        if (result.output != bin) result.output.copyTo(bin, overwrite = true)
        if (result.elf.exists()) result.elf.copyTo(File(dir, "$id.elf"), overwrite = true)
        val sourceText = source.readText()
        File(dir, "$id.ino").writeText(sourceText)
        val meta = JSONObject().put("buildId", id).put("target", target.id)
            .put("sha256", sha).put("sizeBytes", bin.length()).put("createdAt", System.currentTimeMillis())
            .put("sketchName", source.name).put("source", sourceText.take(60_000))
            .put("sourceTruncated", sourceText.length > 60_000)
            .put("sourceScope", "Primary .ino only. Sibling sketch files and external libraries are not included in this source snapshot.")
            .put("log", result.log.takeLast(6000))
        val temp = File(dir, "$id.json.tmp").apply { writeText(meta.toString()) }
        check(temp.renameTo(File(dir, "$id.json"))) { "Could not retain build metadata" }
        return Artifact(id, target, bin, meta)
    }

    fun get(context: Context, id: String): Artifact? {
        if (!id.matches(Regex("(?:esp8266-nodemcu|esp32-s3)-[a-f0-9]{64}"))) return null
        return runCatching {
            val dir = root(context)
            val meta = JSONObject(File(dir, "$id.json").readText())
            val target = McuTarget.entries.first { it.id == meta.getString("target") }
            val bin = File(dir, "$id.bin")
            check(bin.isFile && bin.length() == meta.getLong("sizeBytes"))
            Artifact(id, target, bin, meta)
        }.getOrNull()
    }

    fun list(context: Context): JSONArray = JSONArray().apply {
        root(context).listFiles().orEmpty().filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.take(8).forEach { file ->
                get(context, file.nameWithoutExtension)?.let { artifact ->
                    put(JSONObject(artifact.metadata.toString()).apply { remove("source"); remove("log") })
                }
            }
    }
}

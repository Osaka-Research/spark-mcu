package com.osakaresearch.spark.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A durable working sketch, including failed attempts, separate from flashable builds. */
class AgentWorkspace(private val root: File) {
    companion object { private val lock = Any() }

    fun read(includeSource: Boolean = true): JSONObject = synchronized(lock) {
        val file = File(root, "workspace.json")
        val data = if (file.isFile) JSONObject(file.readText()) else JSONObject().put("revision", "")
        if (!includeSource) data.remove("source")
        data
    }

    fun write(source: String, target: String, expectedRevision: String? = null): JSONObject = synchronized(lock) {
        require(source.isNotBlank() && source.length <= 120_000) { "Supply a complete sketch of 1–120000 characters" }
        require(target in setOf("esp8266-nodemcu", "esp32-s3")) { "Unsupported build target" }
        val old = read()
        if (expectedRevision != null) require(old.optString("revision") == expectedRevision) {
            "The working sketch changed. Read get_workspace and apply your edit to its current revision."
        }
        val revision = MessageDigest.getInstance("SHA-256").digest("$target\n$source".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (old.optString("revision") == revision) return@synchronized old
        val data = JSONObject().put("source", source).put("target", target).put("revision", revision)
            .put("updatedAt", System.currentTimeMillis()).put("lineCount", source.count { it == '\n' } + 1)
        save(data)
        data
    }

    fun patch(expectedRevision: String, search: String, replacement: String): JSONObject = synchronized(lock) {
        val data = read()
        require(data.optString("revision") == expectedRevision) { "Stale revision; read get_workspace before editing" }
        val source = data.getString("source")
        require(search.isNotEmpty()) { "Search text must not be empty" }
        val position = source.indexOf(search)
        require(position >= 0 && source.indexOf(search, position + 1) < 0) {
            "Search text must match exactly once; include more surrounding code"
        }
        write(source.replaceRange(position, position + search.length, replacement), data.getString("target"), expectedRevision)
    }

    fun recordBuild(revision: String, result: JSONObject) = synchronized(lock) {
        val data = read()
        // A compile finishing after another edit must not mark that newer source as built.
        if (data.optString("revision") == revision) {
            data.put("lastBuild", result).put("compiledAt", System.currentTimeMillis())
            save(data)
        }
    }

    private fun save(data: JSONObject) {
        check(root.mkdirs() || root.isDirectory) { "Could not open sketch workspace" }
        val temp = File(root, "workspace.json.tmp").apply { writeText(data.toString()) }
        check(temp.renameTo(File(root, "workspace.json"))) { "Could not save working sketch" }
    }
}

/** Keep the first concrete diagnostics even when the compiler's trailing log is huge. */
object AgentDiagnostics {
    fun compiler(log: String): JSONArray = JSONArray(log.lineSequence().filter {
        it.contains(Regex("(?i)(fatal error:|error:|undefined reference|cannot find|no such file|multiple definition)"))
    }.take(30).map { it.take(1000) }.toList())

    fun serial(lines: List<String>): JSONArray = JSONArray().apply {
        val patterns = linkedMapOf(
            "panic" to Regex("(?i)Guru Meditation|panic'ed|assert failed|abort\\(\\)|Exception \\(\\d+\\)"),
            "watchdog" to Regex("(?i)watchdog|wdt reset|task_wdt"),
            "brownout" to Regex("(?i)brownout"),
            "bootloader" to Regex("(?i)waiting for download|DOWNLOAD\\(USB|DOWNLOAD_BOOT"),
            "backtrace" to Regex("(?i)backtrace:|>>>stack>>>"),
        )
        patterns.forEach { (kind, pattern) ->
            lines.firstOrNull { pattern.containsMatchIn(it) }?.let {
                put(JSONObject().put("kind", kind).put("evidence", it.take(1000)))
            }
        }
    }
}

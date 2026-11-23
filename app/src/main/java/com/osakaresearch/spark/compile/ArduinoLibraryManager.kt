package com.osakaresearch.spark.compile

import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** On-demand Arduino Library Manager installs, pinned to Arduino's indexed archive checksums. */
object ArduinoLibraryManager {
    private const val INDEX_GZ = "https://downloads.arduino.cc/libraries/library_index.json.gz"
    private const val INDEX_MAX_BYTES = 32L * 1024 * 1024
    private const val ARCHIVE_MAX_BYTES = 64L * 1024 * 1024
    private const val EXPANDED_MAX_BYTES = 128L * 1024 * 1024
    private const val INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()

    data class InstallResult(val name: String, val version: String, val dependencies: List<String>)

    fun libraryRoot(context: android.content.Context): File = File(context.filesDir, "arduino/libraries")

    fun installed(context: android.content.Context): List<String> = libraryRoot(context).listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "library.properties").isFile }
        .map { it.name }.sorted()

    suspend fun install(context: android.content.Context, name: String): List<InstallResult> = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._+-]{0,79}"))) { "Invalid Arduino library name" }
        mutex.withLock {
            val installed = mutableListOf<InstallResult>()
            installRecursive(context.applicationContext, name.trim(), linkedSetOf(), installed, 0)
            installed
        }
    }

    private fun installRecursive(
        context: android.content.Context,
        name: String,
        visiting: MutableSet<String>,
        installed: MutableList<InstallResult>,
        depth: Int,
    ) {
        require(depth <= 8) { "Arduino library dependencies exceed the supported depth" }
        val key = name.lowercase(Locale.ROOT)
        if (installed(context).any { it.equals(name, ignoreCase = true) }) return
        if (!visiting.add(key)) return
        try {
            val release = findLatest(context, name)
            val root = libraryRoot(context).apply { mkdirs() }
            val stage = File(root, ".install-${System.nanoTime()}").apply { mkdirs() }
            try {
                val archive = File(stage, "library.zip")
                downloadVerified(release, archive)
                val unpacked = File(stage, "unpacked").apply { mkdirs() }
                extractSafely(archive, unpacked)
                val properties = unpacked.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name == "library.properties" }
                    ?: error("${release.name} archive has no library.properties")
                val props = properties.readLines().mapNotNull { line ->
                    val split = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split('=', limit = 2) ?: return@mapNotNull null
                    split[0].trim() to split.getOrElse(1) { "" }.trim()
                }.toMap()
                val actualName = props["name"]?.takeIf(String::isNotBlank) ?: release.name
                val version = props["version"]?.takeIf(String::isNotBlank) ?: release.version
                val source = properties.parentFile ?: error("Invalid library archive")
                val destination = File(root, safeFolderName(actualName))
                val staged = File(stage, "ready")
                source.copyRecursively(staged, overwrite = true)
                if (destination.exists()) destination.deleteRecursively()
                check(staged.renameTo(destination)) { "Could not install $actualName" }
                installed += InstallResult(actualName, version, emptyList())
                val dependencies = props["depends"].orEmpty().split(',')
                    .map { it.trim().substringBefore('(').trim() }.filter(String::isNotBlank)
                installed[installed.lastIndex] = InstallResult(actualName, version, dependencies)
                dependencies.forEach { installRecursive(context, it, visiting, installed, depth + 1) }
            } finally { stage.deleteRecursively() }
        } finally { visiting.remove(key) }
    }

    private data class Release(val name: String, val version: String, val url: String, val checksum: String)

    private fun findLatest(context: android.content.Context, name: String): Release {
        val index = File(context.filesDir, "arduino/library_index.json.gz")
        if (!index.isFile || System.currentTimeMillis() - index.lastModified() > INDEX_MAX_AGE_MS) {
            index.parentFile?.mkdirs()
            val tmp = File(index.parentFile, "library_index.json.gz.tmp")
            download(INDEX_GZ, tmp, INDEX_MAX_BYTES)
            if (index.exists()) index.delete()
            check(tmp.renameTo(index)) { "Could not cache Arduino's library index" }
        }
        var best: Release? = null
        GZIPInputStream(FileInputStream(index).buffered()).use { gzip ->
            JsonReader(InputStreamReader(gzip, Charsets.UTF_8)).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() == "libraries") {
                        json.beginArray()
                        while (json.hasNext()) {
                            val entry = readRelease(json)
                            if (entry != null && entry.name.equals(name, ignoreCase = true) &&
                                (best == null || compareVersions(entry.version, best!!.version) > 0)) best = entry
                        }
                        json.endArray()
                    } else json.skipValue()
                }
                json.endObject()
            }
        }
        return best ?: error("Arduino Library Manager has no library named '$name'")
    }

    private fun readRelease(json: JsonReader): Release? {
        var name = ""; var version = ""; var url = ""; var checksum = ""
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "name" -> name = json.nextString()
                "version" -> version = json.nextString()
                "url" -> url = json.nextString()
                "checksum" -> checksum = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (name.isNotBlank() && version.isNotBlank() && url.isNotBlank() && checksum.isNotBlank())
            Release(name, version, url, checksum) else null
    }

    private fun downloadVerified(release: Release, destination: File) {
        val url = URL(release.url)
        require(url.protocol == "https") { "Arduino library archive must use HTTPS" }
        download(release.url, destination, ARCHIVE_MAX_BYTES)
        val expected = release.checksum.substringAfter(':', "").trim().lowercase(Locale.ROOT)
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Arduino index has no valid SHA-256 for ${release.name}" }
        val actual = FileInputStream(destination).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(32 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        require(actual == expected) { "Checksum verification failed for ${release.name}; archive discarded" }
    }

    private fun download(url: String, destination: File, maxBytes: Long) {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Only HTTPS library downloads are allowed" }
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) error("Arduino download failed (${it.code})")
            val body = it.body ?: error("Arduino download returned an empty response")
            require(body.contentLength() <= 0 || body.contentLength() <= maxBytes) { "Arduino download exceeds size limit" }
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= maxBytes) { "Arduino download exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun extractSafely(archive: File, destination: File) {
        var total = 0L
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/')
                require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) { "Unsafe path in Arduino library archive" }
                val parts = normalized.split('/').filter(String::isNotBlank)
                if (parts.size < 2) { zip.closeEntry(); continue }
                val output = parts.drop(1).fold(destination) { parent, part -> File(parent, part) }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            total += count; require(total <= EXPANDED_MAX_BYTES) { "Expanded Arduino library exceeds size limit" }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = Regex("\\d+").findAll(a).map { it.value.toLongOrNull() ?: 0L }.toList()
        val right = Regex("\\d+").findAll(b).map { it.value.toLongOrNull() ?: 0L }.toList()
        for (i in 0 until maxOf(left.size, right.size)) {
            val cmp = (left.getOrElse(i) { 0L }).compareTo(right.getOrElse(i) { 0L })
            if (cmp != 0) return cmp
        }
        return a.compareTo(b, ignoreCase = true)
    }

    private fun safeFolderName(name: String): String = name.replace(Regex("[^A-Za-z0-9._+-]"), "_").take(80)
        .ifBlank { error("Invalid Arduino library name") }
}

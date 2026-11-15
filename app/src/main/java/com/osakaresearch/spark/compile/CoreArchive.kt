package com.osakaresearch.spark.compile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compiles arduino-core-esp8266 `cores/esp8266` plus the selected variant
 * into a single static archive, then caches it under `filesDir/build/core/`.
 *
 * The compile is slow (~2–4 min on-phone, ~100 TUs). The cache key is the
 * toolchain VERSION + the flags digest + the set of core source paths, so
 * bumping Toolchain.VERSION or changing BuildFlags invalidates it cleanly.
 */
object CoreArchive {

    private const val SUBDIR = "build/core"
    private const val STAMP = ".built"

    /**
     * Source filenames whose `.o` names the LD script matches by exact basename
     * (via `EXCLUDE_FILE (...)`). These must be archived under their natural
     * name — any prefix would silently misroute them. umm_malloc is the only
     * one in the arduino-core-esp8266 3.1.2 linker script today; extend here
     * if we pick up new EXCLUDE_FILE entries on a core bump.
     */
    private val LD_EXACT_MATCH_SOURCES = setOf("umm_malloc.cpp")

    private val buildMutex = Mutex()

    private fun root(ctx: Context): File = File(ctx.filesDir, SUBDIR)
    fun archive(ctx: Context): File = File(root(ctx), "core.a")

    /**
     * Returns the cached core.a if its stamp matches the current key;
     * otherwise rebuilds it. Safe to call from UI code (progress via onLog).
     */
    suspend fun ensureBuilt(
        ctx: Context,
        onLog: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        buildMutex.withLock {
            Toolchain.ensurePrepared(ctx)

            val root = root(ctx).apply { mkdirs() }
            val stamp = File(root, STAMP)
            val key = cacheKey(ctx)
            val archive = archive(ctx)

            if (archive.exists() && stamp.exists() && stamp.readText().trim() == key) {
                onLog("core.a cached (${archive.length()} bytes)")
                return@withLock archive
            }

            onLog("building core.a (one-time, ~2–4 min) ...")
            root.deleteRecursively(); root.mkdirs()
            val objsDir = File(root, "objs").apply { mkdirs() }

            val gcc = Toolchain.bin(ctx, "xtensa-lx106-elf-gcc")
            val gpp = Toolchain.bin(ctx, "xtensa-lx106-elf-g++")
            val ar = Toolchain.bin(ctx, "xtensa-lx106-elf-ar")
            val binDir = Toolchain.binDir(ctx)
            val sysroot = Toolchain.sysroot(ctx)
            val core = Toolchain.coreDir(ctx)

            val sources = collectCoreSources(core)
            onLog("found ${sources.size} core source files")

            val objs = mutableListOf<File>()
            var i = 0
            for (src in sources) {
                i++
                // Keep the source extension in the object name (e.g. `Arduino.cpp.o`).
                // The Arduino linker script routes code to flash via filename filters
                // like `*.cpp.o(.text*)`; stripping `.cpp`/`.c` drops everything into
                // the `.text1` catch-all and overflows iram1_0_seg.
                //
                // A small set of core files are matched by exact basename in the LD
                // script's EXCLUDE_FILE lists — notably `umm_malloc.cpp.o`, which
                // must land in IRAM (`.text1`) because it services allocations
                // from interrupt context. Prefixing those names with `NNN_` makes
                // the EXCLUDE_FILE pattern miss, silently drops umm_malloc into
                // `.irom0.text`, and produces the epc1=0x402020ec boot loop the
                // moment the SDK first touches heap from an ISR path.
                val keepExactName = src.name in LD_EXACT_MATCH_SOURCES
                val obj = if (keepExactName) {
                    File(objsDir, "${src.name}.o")
                } else {
                    File(objsDir, "${i.toString().padStart(3, '0')}_${src.name}.o")
                }
                val (compiler, flags) = when (src.extension.lowercase()) {
                    "cpp", "cc", "cxx" -> gpp to BuildFlags.cppFlags()
                    "s" -> gcc to BuildFlags.sFlags()
                    else -> gcc to BuildFlags.cFlags()
                }
                val cmd = buildList {
                    add(compiler.absolutePath)
                    add("-B"); add(binDir.absolutePath + "/")
                    addAll(BuildFlags.commonDefines())
                    addAll(BuildFlags.includeDirs(sysroot, core))
                    addAll(flags)
                    add(src.absolutePath)
                    add("-o"); add(obj.absolutePath)
                }
                val (exit, out) = runProcess(cmd, objsDir, binDir)
                if (exit != 0 || !obj.exists()) {
                    onLog("FAIL [$i/${sources.size}] ${src.name}")
                    if (out.isNotBlank()) onLog(out.trimEnd())
                    throw IllegalStateException("core compile failed on ${src.name} (exit=$exit)")
                }
                objs.add(obj)
                if (i % 10 == 0 || i == sources.size) onLog("  compiled $i/${sources.size}")
            }

            onLog("archiving ${objs.size} objs → core.a")
            // ar can't take 100+ args in a single argv on some kernels; feed in chunks.
            archive.delete()
            objs.chunked(40).forEach { chunk ->
                val cmd = buildList {
                    add(ar.absolutePath)
                    add("cru")
                    add(archive.absolutePath)
                    addAll(chunk.map { it.absolutePath })
                }
                val (exit, out) = runProcess(cmd, root, binDir)
                if (exit != 0) {
                    onLog(out.trimEnd())
                    throw IllegalStateException("ar failed (exit=$exit)")
                }
            }

            stamp.writeText(key)
            onLog("core.a ready (${archive.length()} bytes)")
            archive
        }
    }

    private fun collectCoreSources(coreDir: File): List<File> {
        val base = File(coreDir, "cores/esp8266")
        val variant = File(coreDir, "variants/${BuildFlags.VARIANT}")
        val out = mutableListOf<File>()
        for (root in listOf(base, variant)) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("c", "cpp", "cc", "cxx", "s") }
                .forEach { out.add(it) }
        }
        return out.sortedBy { it.absolutePath }
    }

    private fun cacheKey(ctx: Context): String {
        // Toolchain version + flag digest. Source-path set changes are implicit
        // in VERSION bumps since sources ship as assets keyed by VERSION.
        // SCHEMA bump invalidates caches when the object-naming scheme or
        // archive layout changes without any flag/toolchain delta.
        val schema = 3
        val flags = (BuildFlags.commonDefines() +
            BuildFlags.cFlags() + BuildFlags.cppFlags() + BuildFlags.sFlags())
            .joinToString("|")
        return "tcv=${Toolchain.versionTag()};schema=$schema;flags=${flags.hashCode().toString(16)}"
    }

    private fun runProcess(
        cmd: List<String>,
        workDir: File,
        binDir: File,
    ): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        pb.environment()["PATH"] =
            "${binDir.absolutePath}:${pb.environment()["PATH"].orEmpty()}"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return exit to out
    }
}

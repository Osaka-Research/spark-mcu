package com.osakaresearch.spark.compile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full on-device sketch build pipeline:
 *
 *   .ino  →  preprocessIno  →  sketch.cpp
 *   sketch.cpp + Arduino core includes  →  g++ -c  →  sketch.cpp.o
 *   cores/esp8266 sources (one-time, cached)  →  core.a   (CoreArchive)
 *   eagle.app.v6.common.ld.h  →  cpp -E  →  local.eagle.app.v6.common.ld
 *   sketch.cpp.o + core.a + SDK libs + eagle.flash.4m1m.ld  →  ld  →  sketch.elf
 *   eboot.elf + sketch.elf  →  Elf2Image  →  firmware.bin
 *
 * `compileSmokeTest` is kept around as a cheap end-to-end sanity check for
 * the toolchain (no Arduino, no core, no link) — useful when debugging a
 * fresh install or a toolchain bump.
 */
object SketchBuilder {

    sealed class Result {
        data class Ok(val output: File, val elf: File, val log: String) : Result()
        data class Fail(val reason: String, val log: String) : Result()
    }

    /**
     * End-to-end build of a .ino sketch to a flashable `firmware.bin` (eboot
     * bootloader + user app, image_v1 layout). Logs each phase via [onLog]. On
     * first invocation after a toolchain refresh this will take several
     * minutes (core.a build); subsequent calls are fast.
     */
    suspend fun buildSketch(
        ctx: Context,
        inoFile: File,
        target: McuTarget = McuTarget.Default,
        onLog: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (target == McuTarget.ESP32_S3) {
            return@withContext S3SketchBuilder.buildSketch(ctx, inoFile, onLog)
        }
        val logBuf = StringBuilder()
        fun log(line: String) { onLog(line); logBuf.appendLine(line) }

        if (!target.available) {
            val msg = "target '${target.label}' is not available yet: ${target.detail}"
            log(msg)
            return@withContext Result.Fail(msg, logBuf.toString())
        }

        try {
            Toolchain.ensurePrepared(ctx)

            val work = File(ctx.filesDir, "build/sketch").apply {
                deleteRecursively(); mkdirs()
            }
            val sysroot = Toolchain.sysroot(ctx)
            val core = Toolchain.coreDir(ctx)
            val binDir = Toolchain.binDir(ctx)
            val gpp = Toolchain.bin(ctx, "xtensa-lx106-elf-g++")
            val gcc = Toolchain.bin(ctx, "xtensa-lx106-elf-gcc")

            // 1. scan the sketch folder: merge all .ino files into sketch.cpp
            // and collect sibling .cpp/.c/.cc/.cxx/.S sources + extra include
            // dirs (the sketch dir itself, and a src/ subdir if present —
            // matching Arduino's convention).
            log("preprocess: ${inoFile.name}")
            val sketch = preprocessSketch(inoFile, work)
            if (sketch.inoCount > 1) log("  merged ${sketch.inoCount} .ino files")
            if (sketch.extraSources.isNotEmpty()) {
                log("  ${sketch.extraSources.size} sibling source(s)")
            }
            val sketchExtraIncludeFlags = sketch.extraIncludeDirs.map { "-I${it.absolutePath}" }

            // 2. core.a (cached — usually a no-op)
            log("core.a: ensuring built")
            val coreA = CoreArchive.ensureBuilt(ctx, target) { log("  $it") }

            // 3. library discovery — walks every sketch source's #includes and
            // pulls in any shipped arduino-core-esp8266 library that provides a
            // matching header. The resolver walks transitively so a library's
            // own #includes bring in its deps.
            val libSeedFiles = buildList {
                add(sketch.mergedIno)
                addAll(sketch.extraSources)
            }
            val libs = LibraryResolver.resolve(libSeedFiles, core) { log("  $it") }
            if (libs.sources.isNotEmpty()) {
                log("libraries: ${libs.includeDirs.size} dir(s), ${libs.sources.size} src(s)")
            }
            val libIncludeFlags = libs.includeDirs.map { "-I${it.absolutePath}" }

            // 4. sketch.cpp → sketch.o
            log("compile: ${sketch.mergedIno.name}")
            val sketchObj = File(work, "sketch.cpp.o")
            run {
                val cmd = buildList {
                    add(gpp.absolutePath)
                    add("-B"); add(binDir.absolutePath + "/")
                    addAll(BuildFlags.commonDefines())
                    addAll(BuildFlags.includeDirs(sysroot, core))
                    addAll(sketchExtraIncludeFlags)
                    addAll(libIncludeFlags)
                    addAll(BuildFlags.cppFlags())
                    add(sketch.mergedIno.absolutePath)
                    add("-o"); add(sketchObj.absolutePath)
                }
                val (exit, out) = runProcess(cmd, work, binDir)
                if (out.isNotBlank()) log(out.trimEnd())
                if (exit != 0 || !sketchObj.exists()) {
                    return@withContext Result.Fail("sketch compile failed (exit=$exit)", logBuf.toString())
                }
            }

            // 4a. compile sibling .cpp/.c/.S sources sitting next to the .ino
            // (and anything in a src/ subdir). These are linked in alongside
            // sketch.cpp.o so cross-file references resolve.
            val sketchObjs = if (sketch.extraSources.isEmpty()) emptyList() else {
                val siblingDir = File(work, "sketch_siblings").apply { mkdirs() }
                val objs = mutableListOf<File>()
                for ((i, src) in sketch.extraSources.withIndex()) {
                    val obj = File(siblingDir, "${i.toString().padStart(3, '0')}_${src.name}.o")
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
                        addAll(sketchExtraIncludeFlags)
                        addAll(libIncludeFlags)
                        addAll(flags)
                        add(src.absolutePath)
                        add("-o"); add(obj.absolutePath)
                    }
                    val (exit, out) = runProcess(cmd, work, binDir)
                    if (exit != 0 || !obj.exists()) {
                        if (out.isNotBlank()) log(out.trimEnd())
                        return@withContext Result.Fail(
                            "sibling compile failed: ${src.name} (exit=$exit)",
                            logBuf.toString(),
                        )
                    }
                    objs.add(obj)
                }
                log("siblings: compiled ${objs.size} obj(s)")
                objs
            }

            // 4b. compile library sources alongside the sketch. Each library
            // source gets its own .o in work/libs/ — kept per-sketch (not
            // cached like core.a) because the set of libs changes per sketch
            // and recompiles are cheap relative to core.a.
            val libObjs = if (libs.sources.isEmpty()) emptyList() else {
                val libObjsDir = File(work, "libs").apply { mkdirs() }
                val objs = mutableListOf<File>()
                for ((i, src) in libs.sources.withIndex()) {
                    val obj = File(libObjsDir, "${i.toString().padStart(3, '0')}_${src.name}.o")
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
                        addAll(libIncludeFlags)
                        addAll(flags)
                        add(src.absolutePath)
                        add("-o"); add(obj.absolutePath)
                    }
                    val (exit, out) = runProcess(cmd, work, binDir)
                    if (exit != 0 || !obj.exists()) {
                        if (out.isNotBlank()) log(out.trimEnd())
                        return@withContext Result.Fail(
                            "library compile failed: ${src.name} (exit=$exit)",
                            logBuf.toString(),
                        )
                    }
                    objs.add(obj)
                }
                log("libraries: compiled ${objs.size} obj(s)")
                objs
            }

            // 4. linker scripts
            log("linker: generating local.eagle.app.v6.common.ld")
            generateCommonLd(ctx, work, gcc, binDir, sysroot) { log(it) }
            val flashLd = File(work, "local.eagle.flash.ld").apply {
                writeText("INCLUDE \"${File(sysroot, "lib/ld/${BuildFlags.FLASH_LD}").absolutePath}\"\n")
            }

            // 5. link
            log("link: sketch.elf")
            val elf = File(work, "sketch.elf")
            run {
                val cmd = buildList {
                    add(gcc.absolutePath)
                    add("-B"); add(binDir.absolutePath + "/")
                    addAll(BuildFlags.linkFlags())
                    add("-L${sysroot.absolutePath}/lib")
                    add("-L${sysroot.absolutePath}/lib/${BuildFlags.BUILD_SDK}")
                    add("-L${sysroot.absolutePath}/lib/ld")
                    add("-L${sysroot.absolutePath}/lib/gcc/xtensa-lx106-elf/10.3.0")
                    add("-L${work.absolutePath}")
                    add("-T${flashLd.absolutePath}")
                    add("-o"); add(elf.absolutePath)
                    add("-Wl,--start-group")
                    add(sketchObj.absolutePath)
                    addAll(sketchObjs.map { it.absolutePath })
                    addAll(libObjs.map { it.absolutePath })
                    add(coreA.absolutePath)
                    addAll(BuildFlags.linkLibs())
                    add("-Wl,--end-group")
                }
                val (exit, out) = runProcess(cmd, work, binDir)
                if (out.isNotBlank()) log(out.trimEnd())
                if (exit != 0 || !elf.exists()) {
                    return@withContext Result.Fail("link failed (exit=$exit)", logBuf.toString())
                }
            }

            log("built ${elf.name} (${elf.length()} bytes)")

            // Symbol dump — maps boot-time crash PCs (e.g. "epc1=0x402020ec")
            // to the function that owns the address. Cheap to compute and has
            // repeatedly been the fastest way to localise ESP8266 boot crashes.
            runCatching { logKeySymbols(elf, ::log) }
                .onFailure { log("symtab: skipped (${it.message})") }

            // 6. elf → firmware.bin (eboot + app)
            log("elf2image: firmware.bin")
            val bin = File(work, "firmware.bin")
            val ebootElf = File(core, "bootloaders/eboot/eboot.elf")
            if (!ebootElf.exists()) {
                return@withContext Result.Fail(
                    "eboot.elf missing at ${ebootElf.absolutePath}", logBuf.toString()
                )
            }
            val bytes = Elf2Image.build(ebootElf, elf)
            bin.writeBytes(bytes)
            log("built ${bin.name} (${bin.length()} bytes)")

            Result.Ok(bin, elf, logBuf.toString())
        } catch (t: Throwable) {
            Result.Fail(t.message ?: t::class.simpleName.orEmpty(), logBuf.toString())
        }
    }

    /**
     * Writes `int main(void){return 0;}` and compiles it with
     * `xtensa-lx106-elf-gcc -c -mlongcalls -nostdlib`. If this succeeds,
     * the toolchain plumbing (symlinks, PATH, cc1/as discovery via -B)
     * is working end-to-end.
     */
    suspend fun compileSmokeTest(
        ctx: Context,
        onLog: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        Toolchain.ensurePrepared(ctx)

        val work = File(ctx.filesDir, "build/_smoke").apply {
            deleteRecursively(); mkdirs()
        }
        val src = File(work, "main.c").apply {
            writeText("int main(void) { return 0; }\n")
        }
        val obj = File(work, "main.o")

        val gcc = Toolchain.bin(ctx, "xtensa-lx106-elf-gcc")
        val binDir = Toolchain.binDir(ctx)

        val cmd = listOf(
            gcc.absolutePath,
            "-B", binDir.absolutePath + "/",
            "-c", src.absolutePath,
            "-o", obj.absolutePath,
            "-mlongcalls",
            "-nostdlib",
            "-Os",
        )
        onLog("$ ${cmd.joinToString(" ")}")

        val (exit, output) = runProcess(cmd, work, binDir)
        if (output.isNotBlank()) onLog(output.trimEnd())

        when {
            exit != 0 -> Result.Fail("gcc exited with $exit", output)
            !obj.exists() -> Result.Fail("gcc succeeded but no .o produced", output)
            else -> Result.Ok(obj, obj, output)
        }
    }

    /**
     * Logs the symbols the eboot boot flow actually cares about, plus the
     * function nearest to a canary PC in `.irom0.text`. Everything is sorted
     * by address so a reader can eyeball where the IRAM .text ends and
     * `.irom0.text` begins.
     */
    private fun logKeySymbols(elf: File, log: (String) -> Unit) {
        val syms = ElfInspect.functionSymbols(elf)
        log("symtab: ${syms.size} function symbols")
        for (name in listOf("app_entry", "app_entry_redefinable", "call_user_start", "setup", "loop")) {
            val s = ElfInspect.find(syms, name)
            if (s != null) log("  ${name} @ 0x${s.addr.toString(16)} (size=${s.size})")
            else log("  ${name}: <unresolved>")
        }
        // Dump the first few flash-mapped functions so we can eyeball what
        // sits right after the CRC placeholders / .ver_number header.
        val iromStart = 0x40201000L
        val irom = syms.filter { it.addr >= iromStart }.take(6)
        if (irom.isNotEmpty()) log("  first .irom0.text funcs:")
        for (s in irom) log("    0x${s.addr.toString(16)} ${s.name}")
    }

    /**
     * Result of scanning a sketch folder: the merged `.cpp` to compile as the
     * primary TU, the sibling source files that need to be compiled and
     * linked alongside it, and the include dirs to add to `-I` so cross-file
     * `#include "foo.h"` resolves.
     */
    internal data class SketchSources(
        val mergedIno: File,
        val inoCount: Int,
        val extraSources: List<File>,
        val extraIncludeDirs: List<File>,
    )

    /**
     * Arduino-style multi-file sketch scan.
     *
     * The user picks `Sketch/Sketch.ino`; its parent directory may also hold
     * additional `.ino` files (merged into the same TU as `sketch.cpp`, with
     * `#line` directives so compile errors still point at the original file)
     * and sibling `.cpp`/`.c`/`.cc`/`.cxx`/`.S` files (compiled separately
     * and linked in). An optional `src/` subdirectory is walked recursively
     * — this mirrors the Arduino IDE convention where everything else in the
     * sketch folder is treated as data/assets.
     *
     * Full arduino-builder also hoists function prototypes above setup/loop
     * so forward references resolve without manual declarations; that lives
     * in a follow-up. For sketches that declare helpers before using them
     * (or declare prototypes explicitly), this is sufficient.
     */
    internal fun preprocessSketch(primaryIno: File, workDir: File): SketchSources {
        val sketchDir = primaryIno.parentFile ?: workDir

        // All .ino files in the sketch dir (top level only). Primary first,
        // then the rest alphabetically — matches the Arduino IDE ordering.
        val allInos = sketchDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ino", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val orderedInos = buildList {
            add(primaryIno)
            allInos.forEach { if (it != primaryIno && it.absolutePath != primaryIno.absolutePath) add(it) }
        }

        val cpp = File(workDir, "sketch.cpp")
        val sb = StringBuilder()
        sb.append("#include <Arduino.h>\n")
        for (ino in orderedInos) {
            sb.append("#line 1 \"").append(ino.absolutePath).append("\"\n")
            sb.append(runCatching { ino.readText() }.getOrDefault(""))
            if (!sb.endsWith('\n')) sb.append('\n')
        }
        cpp.writeText(sb.toString())

        // Sibling sources: top-level + recursive src/**.
        val siblingExts = setOf("cpp", "cc", "cxx", "c", "s")
        val extraSources = mutableListOf<File>()
        sketchDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in siblingExts }
            ?.sortedBy { it.name }
            ?.forEach { extraSources.add(it) }
        val srcDir = File(sketchDir, "src")
        if (srcDir.isDirectory) {
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in siblingExts }
                .sortedBy { it.absolutePath }
                .forEach { extraSources.add(it) }
        }

        val includeDirs = buildList {
            add(sketchDir)
            if (srcDir.isDirectory) add(srcDir)
        }

        return SketchSources(
            mergedIno = cpp,
            inoCount = orderedInos.size,
            extraSources = extraSources,
            extraIncludeDirs = includeDirs,
        )
    }

    /**
     * Runs the C preprocessor over `eagle.app.v6.common.ld.h` with the same
     * define set we compile with, writing `local.eagle.app.v6.common.ld` next
     * to the generated flash wrapper. The chip-specific flash LD script
     * ends with `INCLUDE "local.eagle.app.v6.common.ld"` — gnu ld finds it
     * via the `-Lwork` we pass to the linker.
     */
    private fun generateCommonLd(
        ctx: Context,
        workDir: File,
        gcc: File,
        binDir: File,
        sysroot: File,
        onLog: (String) -> Unit,
    ) {
        val ldDir = File(sysroot, "lib/ld")
        val src = File(ldDir, "eagle.app.v6.common.ld.h")
        val out = File(workDir, "local.eagle.app.v6.common.ld")
        val cmd = buildList {
            add(gcc.absolutePath)
            add("-B"); add(binDir.absolutePath + "/")
            add("-CC"); add("-E"); add("-P")
            addAll(BuildFlags.commonDefines())
            add("-I${ldDir.absolutePath}")
            add(src.absolutePath)
            add("-o"); add(out.absolutePath)
        }
        val (exit, output) = runProcess(cmd, workDir, binDir)
        if (output.isNotBlank()) onLog(output.trimEnd())
        if (exit != 0 || !out.exists()) {
            throw IllegalStateException("linker-script preprocess failed (exit=$exit)")
        }
    }

    internal fun runProcess(
        cmd: List<String>,
        workDir: File,
        binDir: File,
    ): Pair<Int, String> {
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
        pb.environment()["PATH"] =
            "${binDir.absolutePath}:${pb.environment()["PATH"].orEmpty()}"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return exit to out
    }
}

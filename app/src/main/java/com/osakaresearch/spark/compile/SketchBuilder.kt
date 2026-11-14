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
        data class Ok(val output: File, val log: String) : Result()
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
        onLog: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val logBuf = StringBuilder()
        fun log(line: String) { onLog(line); logBuf.appendLine(line) }

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

            // 1. .ino → .cpp
            log("preprocess: ${inoFile.name}")
            val sketchCpp = preprocessIno(inoFile, work)

            // 2. core.a (cached — usually a no-op)
            log("core.a: ensuring built")
            val coreA = CoreArchive.ensureBuilt(ctx) { log("  $it") }

            // 3. sketch.cpp → sketch.o
            log("compile: ${sketchCpp.name}")
            val sketchObj = File(work, "sketch.cpp.o")
            run {
                val cmd = buildList {
                    add(gpp.absolutePath)
                    add("-B"); add(binDir.absolutePath + "/")
                    addAll(BuildFlags.commonDefines())
                    addAll(BuildFlags.includeDirs(sysroot, core))
                    addAll(BuildFlags.cppFlags())
                    add(sketchCpp.absolutePath)
                    add("-o"); add(sketchObj.absolutePath)
                }
                val (exit, out) = runProcess(cmd, work, binDir)
                if (out.isNotBlank()) log(out.trimEnd())
                if (exit != 0 || !sketchObj.exists()) {
                    return@withContext Result.Fail("sketch compile failed (exit=$exit)", logBuf.toString())
                }
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

            Result.Ok(bin, logBuf.toString())
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
            else -> Result.Ok(obj, output)
        }
    }

    /**
     * Minimal .ino → .cpp conversion: prepend `#include <Arduino.h>`. Full
     * arduino-builder also hoists function prototypes above setup/loop so
     * forward references resolve without manual declarations; that lives in
     * a follow-up once we hit a sketch that needs it. For Blink-level code
     * this is sufficient.
     */
    private fun preprocessIno(ino: File, workDir: File): File {
        val cpp = File(workDir, "sketch.cpp")
        val original = ino.readText()
        val header = "#include <Arduino.h>\n#line 1 \"${ino.absolutePath}\"\n"
        cpp.writeText(header + original)
        return cpp
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

    private fun runProcess(
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

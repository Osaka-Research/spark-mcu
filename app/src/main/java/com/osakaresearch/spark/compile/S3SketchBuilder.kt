package com.osakaresearch.spark.compile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Arduino-ESP32 3.0.5 sketch compile and link path for the ESP32-S3. */
object S3SketchBuilder {
    suspend fun buildSketch(
        ctx: Context,
        inoFile: File,
        onLog: (String) -> Unit,
    ): SketchBuilder.Result = withContext(Dispatchers.IO) {
        val logBuf = StringBuilder()
        fun log(line: String) { onLog(line); logBuf.appendLine(line) }
        val target = McuTarget.ESP32_S3
        if (!target.isAvailable(ctx)) {
            return@withContext SketchBuilder.Result.Fail("ESP32-S3 toolchain and Arduino core are not bundled", "")
        }
        try {
            Toolchain.ensurePrepared(ctx)
            val binDir = Toolchain.binDir(ctx, target)
            val gcc = Toolchain.bin(ctx, "${target.toolchainTriple}-gcc", target)
            val gpp = Toolchain.bin(ctx, "${target.toolchainTriple}-g++", target)
            val core = Toolchain.coreDir(ctx, target)
            val sdk = File(core, "sdk")
            val coreA = File(core, "prebuilt/core.a")
            listOf(gcc, gpp, coreA, File(sdk, "flags/ld_libs")).forEach {
                require(it.isFile) { "ESP32-S3 asset missing: ${it.absolutePath}" }
            }
            val work = File(ctx.filesDir, "build/sketch-s3").apply {
                deleteRecursively(); mkdirs()
            }
            val sketch = SketchBuilder.preprocessSketch(inoFile, work)
            log("preprocess: ${sketch.inoCount} .ino file(s), ${sketch.extraSources.size} sibling source(s)")
            val libs = LibraryResolver.resolve(
                listOf(sketch.mergedIno) + sketch.extraSources, core
            ) { log(it) }

            val defines = listOf(
                "-DF_CPU=240000000L", "-DARDUINO=10819", "-DARDUINO_ESP32S3_DEV",
                "-DARDUINO_ARCH_ESP32", "-DARDUINO_BOARD=\"ESP32S3_DEV\"",
                "-DARDUINO_VARIANT=\"esp32s3\"", "-DARDUINO_PARTITION_default",
                "-DARDUINO_HOST_OS=\"linux\"", "-DARDUINO_FQBN=\"esp32:esp32:esp32s3\"",
                "-DESP32", "-DCORE_DEBUG_LEVEL=0", "-DARDUINO_USB_MODE=1",
                "-DARDUINO_USB_CDC_ON_BOOT=0", "-DARDUINO_USB_MSC_ON_BOOT=0",
                "-DARDUINO_USB_DFU_ON_BOOT=0",
            )
            val includes = buildList {
                add("-I${core.absolutePath}/cores/esp32")
                add("-I${core.absolutePath}/variants/esp32s3")
                sketch.extraIncludeDirs.forEach { add("-I${it.absolutePath}") }
                libs.includeDirs.forEach { add("-I${it.absolutePath}") }
                add("@${File(sdk, "flags/defines").absolutePath}")
                add("-iprefix"); add(File(sdk, "include").absolutePath + "/")
                add("@${File(sdk, "flags/includes").absolutePath}")
                val memoryInclude = File(sdk, "qio_qspi/include")
                if (memoryInclude.isDirectory) add("-I${memoryInclude.absolutePath}")
            }
            val sources = listOf(sketch.mergedIno) + sketch.extraSources + libs.sources
            val objs = mutableListOf<File>()
            for ((index, src) in sources.withIndex()) {
                val obj = File(work, "${index.toString().padStart(3, '0')}_${src.name}.o")
                val cpp = src.extension.lowercase() in setOf("cpp", "cc", "cxx")
                val asm = src.extension.equals("s", ignoreCase = true)
                val compiler = if (cpp) gpp else gcc
                val flagsFile = if (cpp) "cpp_flags" else if (asm) "S_flags" else "c_flags"
                val cmd = buildList {
                    add(compiler.absolutePath)
                    add("-B"); add(binDir.absolutePath + "/")
                    add("@${File(sdk, "flags/$flagsFile").absolutePath}")
                    add("-Os"); add("-Werror=return-type")
                    addAll(defines)
                    addAll(includes)
                    add("-c")
                    if (asm) { add("-x"); add("assembler-with-cpp") }
                    add(src.absolutePath)
                    add("-o"); add(obj.absolutePath)
                }
                val (exit, output) = SketchBuilder.runProcess(cmd, work, binDir)
                if (exit != 0 || !obj.isFile) {
                    log(output.trimEnd())
                    return@withContext SketchBuilder.Result.Fail(
                        "ESP32-S3 compile failed: ${src.name} (exit=$exit)", logBuf.toString()
                    )
                }
                objs.add(obj)
            }
            log("compiled ${objs.size} source file(s)")

            val elf = File(work, "sketch.elf")
            val link = buildList {
                add(gpp.absolutePath)
                add("-B"); add(binDir.absolutePath + "/")
                add("@${File(sdk, "flags/ld_flags").absolutePath}")
                add("@${File(sdk, "flags/ld_scripts").absolutePath}")
                add("-Wl,--Map=${File(work, "sketch.map").absolutePath}")
                add("-L${File(sdk, "lib").absolutePath}")
                add("-L${File(sdk, "ld").absolutePath}")
                val memory = File(sdk, "qio_qspi")
                if (memory.isDirectory) add("-L${memory.absolutePath}")
                add("-Wl,--wrap=esp_panic_handler")
                add("-Wl,--start-group")
                objs.forEach { add(it.absolutePath) }
                add(coreA.absolutePath)
                add("@${File(sdk, "flags/ld_libs").absolutePath}")
                add("-Wl,--end-group")
                add("-Wl,-EL")
                add("-o"); add(elf.absolutePath)
            }
            val (linkExit, linkOutput) = SketchBuilder.runProcess(link, work, binDir)
            if (linkExit != 0 || !elf.isFile) {
                log(linkOutput.trimEnd())
                return@withContext SketchBuilder.Result.Fail(
                    "ESP32-S3 link failed (exit=$linkExit)", logBuf.toString()
                )
            }
            log("linked ${elf.name} (${elf.length()} bytes)")
            val output = File(work, "firmware.bin")
            output.writeBytes(Elf2ImageEsp32.buildApp(elf))
            log("built ${output.name} (${output.length()} bytes) for flash at 0x10000")
            SketchBuilder.Result.Ok(output, elf, logBuf.toString())
        } catch (t: Throwable) {
            log(t.message ?: t.javaClass.simpleName)
            SketchBuilder.Result.Fail("ESP32-S3 build failed", logBuf.toString())
        }
    }
}

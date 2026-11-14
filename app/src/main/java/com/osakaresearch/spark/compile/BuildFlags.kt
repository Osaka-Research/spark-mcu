package com.osakaresearch.spark.compile

import java.io.File

/**
 * Compile/link flags transcribed from arduino-core-esp8266 3.1.2 platform.txt
 * for a NodeMCU board on the default lwip2-1460-feat variant, 80 MHz, VTABLES
 * in flash, exceptions disabled. See platform.txt + boards.txt in assets for
 * the authoritative source — this mirrors the generic/nodemcu defaults.
 *
 * Anything board- or menu-configurable lives here so callers stay readable.
 * When we add a board picker, swap this out for something parameterised.
 */
object BuildFlags {
    const val VARIANT = "nodemcu"
    const val BUILD_SDK = "NONOSDK22x_190703"
    const val F_CPU = "80000000L"
    const val ARDUINO_IDE_VERSION = 10819
    const val ARDUINO_BOARD = "ESP8266_NODEMCU_ESP12"
    const val ARDUINO_BOARD_ID = "nodemcu"
    const val FLASH_LD = "eagle.flash.4m1m.ld"

    /** Defines shared by C, C++, and assembler. */
    fun commonDefines(): List<String> = listOf(
        "-D__ets__",
        "-DICACHE_FLASH",
        "-U__STRICT_ANSI__",
        "-D_GNU_SOURCE",
        "-DESP8266",
        "-D$BUILD_SDK=1",
        "-DF_CPU=$F_CPU",
        "-DARDUINO=$ARDUINO_IDE_VERSION",
        "-DARDUINO_$ARDUINO_BOARD",
        "-DARDUINO_ARCH_ESP8266",
        "-DARDUINO_BOARD=\"$ARDUINO_BOARD\"",
        "-DARDUINO_BOARD_ID=\"$ARDUINO_BOARD_ID\"",
        "-DVTABLES_IN_FLASH",
        "-DLWIP_OPEN_SRC",
        "-DTCP_MSS=1460",
        "-DLWIP_FEATURES=1",
        "-DLWIP_IPV6=0",
        "-DNONOSDK=0x$BUILD_SDK",
        // ARDUINO_ESP8266_RELEASE[_x_y_z] come from core_version.h; defining
        // them here again just produces redefinition warnings with a mismatched
        // format (underscores vs. dots).
        "-DMMU_IRAM_SIZE=0x8000",
        "-DMMU_ICACHE_SIZE=0x8000",
    )

    fun includeDirs(sysroot: File, coreDir: File): List<String> {
        val gccIncRoot = "${sysroot.absolutePath}/lib/gcc/xtensa-lx106-elf/$GCC_VER"
        val cppInc = "${sysroot.absolutePath}/include/c++/$GCC_VER"
        // Order mirrors gcc's own `--print-search-dirs` layout: C++ first, then
        // gcc intrinsics, then newlib. #include_next from cstdlib walks forward
        // in this list to reach <sysroot>/include/stdlib.h, so newlib MUST come
        // after the C++ include dir.
        return listOf(
            "-I${coreDir.absolutePath}/cores/esp8266",
            "-I${coreDir.absolutePath}/variants/$VARIANT",
            "-I$cppInc/xtensa-lx106-elf",
            "-I$cppInc",
            "-I$cppInc/backward",
            "-I$gccIncRoot/include",
            "-I$gccIncRoot/include-fixed",
            "-I${sysroot.absolutePath}/include",
            "-I${sysroot.absolutePath}/lwip2/include",
        )
    }

    private const val GCC_VER = "10.3.0"

    /** `xtensa-lx106-elf-gcc`-style C flags; drop `-c` if you need to compile+link. */
    fun cFlags(): List<String> = listOf(
        "-c",
        "-std=gnu17",
        "-Os", "-g",
        "-free", "-fipa-pta",
        "-Werror=return-type",
        "-Wpointer-arith",
        "-Wno-implicit-function-declaration",
        "-Wl,-EL",
        "-fno-inline-functions",
        "-nostdlib",
        "-mlongcalls",
        "-mtext-section-literals",
        "-falign-functions=4",
        "-ffunction-sections",
        "-fdata-sections",
        "-fno-exceptions",
    )

    fun cppFlags(): List<String> = listOf(
        "-c",
        "-std=gnu++17",
        "-Os", "-g",
        "-free", "-fipa-pta",
        "-Werror=return-type",
        "-mlongcalls",
        "-mtext-section-literals",
        "-fno-rtti",
        "-falign-functions=4",
        "-nostdlib",
        "-ffunction-sections",
        "-fdata-sections",
        "-fno-exceptions",
    )

    fun sFlags(): List<String> = listOf(
        "-c", "-g",
        "-x", "assembler-with-cpp",
        "-mlongcalls",
    )

    /** Linker flags excluding `-T` and `-L` dirs — those depend on runtime paths. */
    fun linkFlags(): List<String> = listOf(
        "-g", "-Os",
        "-nostdlib",
        "-Wl,--no-check-sections",
        "-u", "app_entry",
        "-u", "_printf_float",
        "-u", "_scanf_float",
        "-Wl,-static",
        "-Wl,--gc-sections",
        "-Wl,-wrap,system_restart_local",
        "-Wl,-wrap,spi_flash_read",
    )

    /** Order matters — gcc's linker is single-pass. */
    fun linkLibs(): List<String> = listOf(
        "-lhal", "-lphy", "-lpp", "-lnet80211",
        "-llwip2-1460-feat",
        "-lwpa", "-lcrypto", "-lmain", "-lwps", "-lbearssl",
        "-lespnow", "-lsmartconfig", "-lairkiss", "-lwpa2",
        "-lstdc++", "-lm", "-lc", "-lgcc",
    )
}

package com.osakaresearch.spark.compile

import android.content.Context

/**
 * The compile + flash pipeline is MCU-family-specific: toolchain prefix,
 * Arduino core, LD scripts, image layout, and flasher protocol all differ
 * between ESP8266 (xtensa-lx106, image_v1, 1 MB app, CH340/CP2102 USB) and
 * ESP32-S3 (xtensa-esp32s3, image_v2, partition table, native USB CDC).
 *
 * This enum is the single discriminator the pipeline keys off — call sites
 * branch on `target` rather than carrying parallel booleans. New MCU
 * families (ESP32-C3/C6/H2, RP2040, etc.) slot in by adding a case and the
 * matching toolchain + core assets.
 *
 * `ldExactMatchSources` is the set of source filenames whose `.o` names must
 * be preserved verbatim when archived into core.a — the arduino cores'
 * linker scripts match these by exact basename via `EXCLUDE_FILE (...)` and
 * misrouting them silently (prefix-renaming them) produces boot loops
 * (ESP8266 umm_malloc into flash → epc1=0x402020ec; see
 * project_spark_ld_exact_match memory).
 */
enum class McuTarget(
    val id: String,
    val label: String,
    val detail: String,
    val available: Boolean,
    val toolchainTriple: String,
    val gccVer: String,
    val coreAssetSubdir: String,
    val ldExactMatchSources: Set<String>,
) {
    ESP8266_NODEMCU(
        id = "esp8266-nodemcu",
        label = "ESP8266 · NodeMCU",
        detail = "xtensa-lx106 · 4M/1M · 80 MHz · DIO",
        available = true,
        toolchainTriple = "xtensa-lx106-elf",
        gccVer = "10.3.0",
        coreAssetSubdir = "toolchain/arduino-core-esp8266",
        ldExactMatchSources = setOf("umm_malloc.cpp"),
    ),
    ESP32_S3(
        id = "esp32-s3",
        label = "ESP32-S3",
        detail = "xtensa-esp32s3 · app image at 0x10000",
        available = false,
        toolchainTriple = "xtensa-esp32s3-elf",
        gccVer = "13.2.0",
        coreAssetSubdir = "toolchain/arduino-core-esp32",
        ldExactMatchSources = emptySet(),
    ),
    ESP32_CAM(
        id = "esp32-cam",
        label = "ESP32-CAM · AI-Thinker",
        // Original ESP32 (LX6, dual-core) with 4 MB PSRAM and an OV2640
        // module. Same image_v2 format as S3 but different chip_id (0x0000)
        // and classic flash layout: bootloader at 0x1000, not 0x0.
        detail = "xtensa-esp32 · OV2640 · PSRAM",
        available = false,
        toolchainTriple = "xtensa-esp32-elf",
        gccVer = "13.2.0",
        coreAssetSubdir = "toolchain/arduino-core-esp32cam",
        ldExactMatchSources = emptySet(),
    ),
    ;

    val toolchainAssetSubdir: String get() = "toolchain/$toolchainTriple"

    fun isAvailable(ctx: Context): Boolean = when (this) {
        ESP8266_NODEMCU -> true
        ESP32_S3 -> ctx.assets.list("toolchain/arduino-core-esp32").orEmpty().isNotEmpty() &&
            ctx.assets.list(toolchainAssetSubdir).orEmpty().isNotEmpty()
        ESP32_CAM -> ctx.assets.list(coreAssetSubdir).orEmpty().isNotEmpty() &&
            ctx.assets.list(toolchainAssetSubdir).orEmpty().isNotEmpty()
    }

    companion object {
        val Default: McuTarget = ESP8266_NODEMCU
        fun byId(id: String): McuTarget = values().firstOrNull { it.id == id } ?: Default
    }
}

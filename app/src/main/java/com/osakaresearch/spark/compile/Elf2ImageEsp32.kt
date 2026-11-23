package com.osakaresearch.spark.compile

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Pure-Kotlin port of esptool's `ESP32FirmwareImage.save()` (image_v2 writer).
 *
 * Produces the single app `firmware.bin` that gets flashed at 0x10000 on an
 * ESP32-S3. The S3 ROM boots the second-stage bootloader from 0x0, which then
 * loads this app. Format:
 *
 *   [0..7]   common header  — magic (0xE9), seg count, flash mode, size/freq, entry point
 *   [8..23]  extended header — wp_pin, spi drives, chip_id, rev bounds, hash flag
 *   [24..]   segments (header: load_addr + size, then data), padded + checksum,
 *            then SHA-256 digest over everything prior (bootloader verifies it).
 *
 * The non-obvious piece is flash-segment alignment. S3 memory-maps flash via
 * the MMU on 64 KB pages, so any segment loading into IROM/DROM must land at
 * an offset in the image whose low 16 bits match the segment's load address.
 * Flash sections are written first, with RAM data filling alignment gaps.
 * This keeps esp_app_desc at image offset 0x20 as required by ESP-IDF.
 */
object Elf2ImageEsp32 {

    private const val MAGIC = 0xE9
    private const val CHECKSUM_SEED = 0xEF
    private const val SEG_HEADER_LEN = 8
    private const val IROM_ALIGN = 0x10000
    private const val WP_PIN_DISABLED = 0xEE

    /** Chip IDs as understood by the ESP32 image format / ROM loader. */
    const val CHIP_ID_ESP32    = 0x0000
    const val CHIP_ID_ESP32_S2 = 0x0002
    const val CHIP_ID_ESP32_C3 = 0x0005
    const val CHIP_ID_ESP32_S3 = 0x0009

    /**
     * Flash parameter byte encodings for ESP32 family — nibble maps differ from
     * the ESP8266 encoding ([Elf2Image.FlashParams]) so we keep them separate.
     *
     * Defaults match arduino-esp32's generic ESP32-S3 Dev Module:
     *   mode = DIO (lowest-common-denominator)
     *   size = 4 MB
     *   freq = 80 MHz
     */
    data class FlashParams(
        val mode: Int = 2,       // 0=qio 1=qout 2=dio 3=dout
        val sizeMb: Int = 4,
        val freqMhz: Int = 80,
    ) {
        val modeByte: Int get() = mode and 0xFF
        val sizeFreqByte: Int get() {
            val sizeNibble = when (sizeMb) {
                1 -> 0; 2 -> 1; 4 -> 2; 8 -> 3; 16 -> 4; 32 -> 5; 64 -> 6; 128 -> 7
                else -> throw IllegalArgumentException("unsupported flash size: $sizeMb MB")
            }
            val freqNibble = when (freqMhz) {
                40 -> 0; 26 -> 1; 20 -> 2; 80 -> 0xF
                else -> throw IllegalArgumentException("unsupported flash freq: $freqMhz MHz")
            }
            return ((sizeNibble and 0xF) shl 4) or (freqNibble and 0xF)
        }
    }

    /**
     * Address ranges that are memory-mapped from flash via the MMU. Segments
     * loading into these regions need 64 KB-aligned placement in the image.
     * Values from arduino-esp32 variants/esp32s3/{tools/,}sdkconfig.h /
     * esp-idf soc/esp32s3/include/soc/soc.h.
     */
    private fun isFlashAddr(addr: Long, chipId: Int): Boolean = when (chipId) {
        CHIP_ID_ESP32_S3 -> (addr in 0x42000000L..0x43FFFFFFL) || // IROM
                           (addr in 0x3C000000L..0x3DFFFFFFL)    // DROM
        CHIP_ID_ESP32    -> (addr in 0x400D0000L..0x40400000L) || // IROM
                           (addr in 0x3F400000L..0x3F800000L)    // DROM
        CHIP_ID_ESP32_S2 -> (addr in 0x40080000L..0x40800000L) ||
                           (addr in 0x3F000000L..0x3F3FE000L)
        CHIP_ID_ESP32_C3 -> (addr in 0x42000000L..0x42800000L) ||
                           (addr in 0x3C000000L..0x3C800000L)
        else -> false
    }

    /**
     * Build a flashable S3 app image from a linked ELF. Caller writes the
     * returned bytes to e.g. `firmware.bin` and flashes at 0x10000 (the app
     * partition offset defined by `partitions.bin` in the standard
     * arduino-esp32 OTA layout — same layout the precompiled-blink path
     * uses, see [com.osakaresearch.spark.net.PrecompiledBlobs]).
     */
    fun buildApp(
        appElf: File,
        chipId: Int = CHIP_ID_ESP32_S3,
        flash: FlashParams = FlashParams(),
        minChipRevFull: Int = 0,
        maxChipRevFull: Int = 0xFFFF,
    ): ByteArray {
        val parsed = parseElf(appElf)
        val sections = parsed.loadSegments
        require(sections.isNotEmpty()) { "${appElf.name}: no loadable sections with data" }

        // ESP-IDF reserves this field in .flash.appdesc for the ELF digest.
        // Patch before computing either the segment checksum or image digest.
        val appDesc = sections.firstOrNull {
            it.name == ".flash.appdesc" && isFlashAddr(it.addr, chipId)
        }
        if (appDesc != null) {
            require(appDesc.data.size >= 0xB0 &&
                appDesc.data.sliceArray(0x90 until 0xB0).all { it == 0.toByte() }) {
                "${appElf.name}: invalid ELF digest field in app description"
            }
            val elfHash = MessageDigest.getInstance("SHA-256")
            appElf.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    elfHash.update(buffer, 0, count)
                }
            }
            elfHash.digest().copyInto(appDesc.data, 0x90)
        }

        // Merge contiguous sections before aligning flash mappings. Otherwise
        // .flash.appdesc and .flash.rodata would occupy separate MMU pages.
        val allSegments = mutableListOf<LoadSegment>()
        for (section in sections.sortedBy { it.addr }) {
            val previous = allSegments.lastOrNull()
            if (previous != null && previous.addr + previous.data.size == section.addr &&
                isFlashAddr(previous.addr, chipId) == isFlashAddr(section.addr, chipId)) {
                allSegments[allSegments.lastIndex] = previous.copy(data = previous.data + section.data)
            } else {
                allSegments.add(section)
            }
        }
        val ramSegs = allSegments.filterNot { isFlashAddr(it.addr, chipId) }.toMutableList()
        val flashSegs = allSegments.filter { isFlashAddr(it.addr, chipId) }.sortedBy { it.addr }
        flashSegs.zipWithNext().forEach { (a, b) ->
            require(a.addr / IROM_ALIGN != b.addr / IROM_ALIGN) {
                "${appElf.name}: separate flash sections share an MMU page"
            }
        }
        if (appDesc != null) {
            require(flashSegs.firstOrNull()?.addr == appDesc.addr && appDesc.addr % IROM_ALIGN == 0x20L) {
                "${appElf.name}: app description must be the first flash data at offset 0x20"
            }
        }

        val out = ByteArrayOutputStream()
        // Reserve header bytes; we patch num_segments at the end since the
        // padding-segment count is only known once we've placed flash segments.
        val headerStart = out.size()
        writeHeaderPlaceholder(out, flash, parsed.entry, chipId, minChipRevFull, maxChipRevFull)

        var checksum = CHECKSUM_SEED
        var segCount = 0

        fun appendSegment(seg: LoadSegment) {
            writeSegment(out, seg)
            checksum = seg.data.fold(checksum) { acc, b -> acc xor (b.toInt() and 0xFF) }
            segCount++
        }

        for (seg in flashSegs) {
            while ((out.size() + SEG_HEADER_LEN) % IROM_ALIGN != (seg.addr % IROM_ALIGN).toInt()) {
                // Match esptool's placement on the next MMU page, including
                // cases where the required padding exceeds one page.
                var padLen = IROM_ALIGN - out.size() % IROM_ALIGN +
                    (seg.addr % IROM_ALIGN).toInt() - 2 * SEG_HEADER_LEN
                if (padLen < 0) padLen += IROM_ALIGN
                if (ramSegs.isNotEmpty() && padLen > SEG_HEADER_LEN) {
                    val ram = ramSegs.removeAt(0)
                    val count = minOf(padLen, ram.data.size)
                    appendSegment(ram.copy(data = ram.data.copyOfRange(0, count)))
                    if (count < ram.data.size) {
                        ramSegs.add(0, ram.copy(addr = ram.addr + count,
                            data = ram.data.copyOfRange(count, ram.data.size)))
                    }
                } else {
                    appendSegment(LoadSegment(0L, ByteArray(padLen)))
                }
            }
            appendSegment(seg)
        }
        ramSegs.forEach { appendSegment(it) }
        require(segCount in 1..16) { "${appElf.name}: image has too many segments ($segCount)" }

        // esptool/elf2bin convention: pad with zeros so the checksum byte lands
        // at (next multiple of 16) - 1. Image length before SHA-256 is therefore
        // a multiple of 16 — which the hash step also needs.
        while ((out.size() % 16) != 15) out.write(0)
        out.write(checksum and 0xFF)

        // Patch segment_count byte now that padding segments are counted.
        val bytes = out.toByteArray()
        bytes[headerStart + 1] = (segCount and 0xFF).toByte()

        // SHA-256 over the complete image — bootloader recomputes and rejects
        // the image if the trailer doesn't match.
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return bytes + digest
    }

    private fun writeHeaderPlaceholder(
        out: OutputStream,
        flash: FlashParams,
        entry: Long,
        chipId: Int,
        minChipRevFull: Int,
        maxChipRevFull: Int,
    ) {
        // Common header (8 bytes). num_segments is a placeholder (0); we patch
        // it in-place after writing all segments and padding.
        out.write(MAGIC)
        out.write(0)
        out.write(flash.modeByte)
        out.write(flash.sizeFreqByte)
        writeU32Le(out, entry)

        // Extended header (16 bytes) — matches docs.espressif.com "Application
        // Image Format" table.
        out.write(WP_PIN_DISABLED)
        out.write(0); out.write(0); out.write(0)   // SPI drive settings (clk/q, d/cs, hd/wp)
        writeU16Le(out, chipId)
        out.write(0)                               // min_chip_rev (deprecated; use *_full)
        writeU16Le(out, minChipRevFull)
        writeU16Le(out, maxChipRevFull)
        repeat(4) { out.write(0) }                 // reserved
        out.write(1)                               // hash_appended = 1
    }

    private fun writeSegment(out: OutputStream, seg: LoadSegment) {
        require(seg.data.size % 4 == 0 && seg.data.size < 0x1000000) {
            "invalid image segment length: ${seg.data.size}"
        }
        writeU32Le(out, seg.addr)
        writeU32Le(out, seg.data.size.toLong())
        out.write(seg.data)
    }

    private data class LoadSegment(val addr: Long, val data: ByteArray, val name: String = "")
    private data class ParsedElf(val entry: Long, val loadSegments: List<LoadSegment>)

    /**
     * Read initialized ELF32-LE sections, matching esptool's default elf2image
     * input. BSS and other NOBITS sections are initialized by startup code.
     */
    private fun parseElf(file: File): ParsedElf {
        RandomAccessFile(file, "r").use { raf ->
            val ehdr = ByteArray(0x34)
            raf.readFully(ehdr)
            val bb = ByteBuffer.wrap(ehdr).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.get(0).toInt() != 0x7F ||
                bb.get(1).toInt().toChar() != 'E' ||
                bb.get(2).toInt().toChar() != 'L' ||
                bb.get(3).toInt().toChar() != 'F'
            ) throw IllegalStateException("${file.name}: missing ELF magic")
            if (bb.get(4).toInt() != 1) throw IllegalStateException("${file.name}: not ELF32")
            if (bb.get(5).toInt() != 1) throw IllegalStateException("${file.name}: not little-endian")

            val entry     = bb.getInt(24).toLong() and 0xFFFFFFFFL
            val shoff = bb.getInt(32).toLong() and 0xFFFFFFFFL
            val shentsize = bb.getShort(46).toInt() and 0xFFFF
            val shnum = bb.getShort(48).toInt() and 0xFFFF
            val shstrndx = bb.getShort(50).toInt() and 0xFFFF
            require(shentsize == 0x28 && shnum > 0 && shstrndx in 0 until shnum) {
                "${file.name}: invalid ELF section table"
            }
            fun readData(offset: Long, size: Long): ByteArray {
                require(size in 0..Int.MAX_VALUE.toLong() && offset >= 0 && offset + size <= raf.length()) {
                    "${file.name}: ELF section extends beyond file"
                }
                raf.seek(offset)
                return ByteArray(size.toInt()).also { raf.readFully(it) }
            }
            val shb = ByteBuffer.wrap(readData(shoff, (shnum * shentsize).toLong()))
                .order(ByteOrder.LITTLE_ENDIAN)
            fun field(index: Int, offset: Int): Long =
                shb.getInt(index * shentsize + offset).toLong() and 0xFFFFFFFFL
            val names = readData(field(shstrndx, 16), field(shstrndx, 20))

            val segs = mutableListOf<LoadSegment>()
            for (i in 0 until shnum) {
                // PROGBITS, INIT_ARRAY, FINI_ARRAY and PREINIT_ARRAY.
                if (field(i, 4).toInt() !in setOf(1, 14, 15, 16)) continue
                val addr = field(i, 12)
                val size = field(i, 20)
                if (addr == 0L || size == 0L) continue
                val nameOffset = field(i, 0).toInt()
                require(nameOffset in names.indices) { "${file.name}: invalid section name" }
                var nameEnd = nameOffset
                while (nameEnd < names.size && names[nameEnd] != 0.toByte()) nameEnd++
                val name = String(names, nameOffset, nameEnd - nameOffset, Charsets.UTF_8)
                val data = readData(field(i, 16), size)
                segs.add(LoadSegment(addr, data.copyOf((data.size + 3) and -4), name))
            }
            return ParsedElf(entry, segs)
        }
    }

    private fun writeU16Le(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeU32Le(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8)  and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }
}

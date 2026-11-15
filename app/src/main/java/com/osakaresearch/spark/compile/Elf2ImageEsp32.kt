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
 * Esptool achieves this by inserting dummy "padding segments" (addr=0, zero
 * data) before each flash segment. We do the same, because arduino-esp32's
 * linker emits RAM and flash segments interleaved and the ROM loader won't
 * remap them for us.
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
        val allSegments = parsed.loadSegments
        require(allSegments.isNotEmpty()) { "${appElf.name}: no PT_LOAD segments with data" }

        // Esptool writes all RAM segments first, then each flash segment preceded
        // by a padding segment that eats bytes until the flash segment's data
        // lands on a 64 KB boundary matching its load address.
        val ramSegs   = allSegments.filterNot { isFlashAddr(it.addr, chipId) }
        val flashSegs = allSegments.filter { isFlashAddr(it.addr, chipId) }.sortedBy { it.addr }

        val out = ByteArrayOutputStream()
        // Reserve header bytes; we patch num_segments at the end since the
        // padding-segment count is only known once we've placed flash segments.
        val headerStart = out.size()
        writeHeaderPlaceholder(out, flash, parsed.entry, chipId, minChipRevFull, maxChipRevFull)

        var checksum = CHECKSUM_SEED
        var segCount = 0

        for (seg in ramSegs) {
            writeSegment(out, seg)
            checksum = seg.data.fold(checksum) { acc, b -> acc xor (b.toInt() and 0xFF) }
            segCount++
        }

        for (seg in flashSegs) {
            // Where seg.data WOULD land if we wrote it with no padding before.
            val segmentDataOffset = out.size() + SEG_HEADER_LEN
            var padLen = (seg.addr.toInt() - segmentDataOffset).mod(IROM_ALIGN)
            if (padLen != 0) {
                // The pad segment carries its own 8-byte header, so subtract
                // that from its data length. If the remainder is negative,
                // bump by one full IROM_ALIGN so the real segment still ends
                // up aligned on the following page.
                padLen -= SEG_HEADER_LEN
                if (padLen < 0) padLen += IROM_ALIGN
                writeSegment(out, LoadSegment(0L, ByteArray(padLen)))
                // Pad data is zero, so it contributes 0 to the XOR checksum.
                segCount++
            }
            writeSegment(out, seg)
            checksum = seg.data.fold(checksum) { acc, b -> acc xor (b.toInt() and 0xFF) }
            segCount++
        }

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
        writeU32Le(out, seg.addr)
        writeU32Le(out, seg.data.size.toLong())
        out.write(seg.data)
    }

    private data class LoadSegment(val addr: Long, val data: ByteArray)
    private data class ParsedElf(val entry: Long, val loadSegments: List<LoadSegment>)

    /**
     * ELF32-LE program-header reader. Unlike the ESP8266 path we deliberately
     * do NOT walk section headers — esptool's reference implementation reads
     * PT_LOAD program headers, and matching that is the only way to end up
     * byte-identical to arduino-cli output for the same ELF.
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
            val phoff     = bb.getInt(28).toLong() and 0xFFFFFFFFL
            val phentsize = bb.getShort(42).toInt() and 0xFFFF
            val phnum     = bb.getShort(44).toInt() and 0xFFFF
            if (phentsize != 0x20) throw IllegalStateException(
                "${file.name}: unexpected program header entry size 0x${phentsize.toString(16)}"
            )

            raf.seek(phoff)
            val phBytes = ByteArray(phnum * phentsize)
            raf.readFully(phBytes)
            val phb = ByteBuffer.wrap(phBytes).order(ByteOrder.LITTLE_ENDIAN)

            val segs = mutableListOf<LoadSegment>()
            for (i in 0 until phnum) {
                val base   = i * phentsize
                val type   = phb.getInt(base)
                if (type != 1) continue  // PT_LOAD only
                val offset = phb.getInt(base + 4).toLong() and 0xFFFFFFFFL
                val paddr  = phb.getInt(base + 12).toLong() and 0xFFFFFFFFL
                val filesz = phb.getInt(base + 16).toLong() and 0xFFFFFFFFL
                if (filesz == 0L) continue
                raf.seek(offset)
                val data = ByteArray(filesz.toInt())
                raf.readFully(data)
                segs.add(LoadSegment(paddr, data))
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

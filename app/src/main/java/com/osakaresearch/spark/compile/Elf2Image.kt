package com.osakaresearch.spark.compile

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin port of arduino-core-esp8266 3.1.2's `tools/elf2bin.py`. Produces
 * the Arduino-compatible .bin that gets flashed at 0x0 on an ESP8266:
 *
 *   [0x0000..0x0FFF] eboot image (image_v1; segments: .text, .rodata)
 *                    padded to 4 KB with 0xAA.
 *   [0x1000..]       user-app image (image_v1 with .irom0.text as the first
 *                    segment; also .text, .text1, .data, .rodata).
 *
 * eboot validates the app by reading two 32-bit fields at the start of the app
 * image's .irom0.text data (__crc_len / __crc_val, reserved in the LD script).
 * [patchCrc] fills these in after the whole file is assembled.
 */
object Elf2Image {
    private const val IMAGE_V1_MAGIC = 0xE9
    private const val CHECKSUM_SEED = 0xEF
    private const val EBOOT_PAD_SIZE = 4096
    private const val CRC_SIZE_OFFSET = EBOOT_PAD_SIZE + 16
    private const val CRC_VAL_OFFSET = EBOOT_PAD_SIZE + 20

    /**
     * Flash parameter byte encodings, mirrored from elf2bin.py. Defaults match
     * nodemcu's 4M flash / 40 MHz / QIO configuration — the same as BuildFlags.
     */
    data class FlashParams(
        // DIO is the lowest-common-denominator SPI mode that every ESP8266
        // module can read. QIO is ~30% faster but needs the flash chip to
        // support quad reads AND the WP/HOLD pads to be strapped — many
        // modern ESP-12E/F variants (PUYA, newer Winbond) only read
        // reliably as DIO. A QIO/DIO mismatch produces a "Fatal exception
        // (0): epc1=0x40202x" boot loop (chip executes garbage from flash).
        val mode: Int = 2,     // dio
        val sizeMb: Int = 4,   // 4MB → encoded as nibble 4
        val freqMhz: Int = 40, // 40 MHz → encoded as nibble 0
    ) {
        val modeByte: Int get() = mode and 0xFF
        val sizeFreqByte: Int get() {
            val sizeNibble = when (sizeMb) {
                256 -> 1; 512 -> 0; 1 -> 2; 2 -> 3; 4 -> 4; 8 -> 8; 16 -> 9
                else -> throw IllegalArgumentException("unsupported flash size: $sizeMb MB")
            }
            val freqNibble = when (freqMhz) {
                40 -> 0; 26 -> 1; 20 -> 2; 80 -> 15
                else -> throw IllegalArgumentException("unsupported flash freq: $freqMhz MHz")
            }
            return ((sizeNibble and 0xF) shl 4) or (freqNibble and 0xF)
        }
    }

    /** Builds the full flashable image (eboot + app) ready to be written at flash offset 0. */
    fun build(
        ebootElf: File,
        appElf: File,
        flash: FlashParams = FlashParams(),
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // eboot: .text + .rodata, padded to 4 KB with 0xAA.
        writeImage(out, ebootElf, listOf(".text", ".rodata"), padTo = EBOOT_PAD_SIZE, flash)
        check(out.size() == EBOOT_PAD_SIZE) {
            "eboot image expected to land at 0x1000 but ended at 0x${out.size().toString(16)}"
        }

        // User app: irom first (required by eboot), then everything that
        // loads into RAM at boot.
        writeImage(
            out, appElf,
            listOf(".irom0.text", ".text", ".text1", ".data", ".rodata"),
            padTo = 0, flash,
        )

        val bytes = out.toByteArray()
        patchCrc(bytes)
        return bytes
    }

    private fun writeImage(
        out: ByteArrayOutputStream,
        elf: File,
        segmentNames: List<String>,
        padTo: Int,
        flash: FlashParams,
    ) {
        val parsed = parseElf(elf)
        // Missing or empty sections are skipped — matches the practical behaviour of
        // Arduino builds where e.g. an all-flash sketch may have an empty .text1.
        val segments = segmentNames.mapNotNull { name ->
            parsed.sections[name]?.takeIf { it.data.isNotEmpty() }
        }

        // v1 header: magic, numSegments, flashMode, sizeFreq, entryPoint (LE).
        out.write(IMAGE_V1_MAGIC)
        out.write(segments.size and 0xFF)
        out.write(flash.modeByte)
        out.write(flash.sizeFreqByte)
        writeU32Le(out, parsed.entry)

        var runningSize = 8
        var checksum = CHECKSUM_SEED
        for (seg in segments) {
            writeU32Le(out, seg.addr)
            writeU32Le(out, seg.data.size.toLong())
            out.write(seg.data)
            runningSize += 8 + seg.data.size
            for (b in seg.data) checksum = checksum xor (b.toInt() and 0xFF)
        }

        // esptool/elf2bin convention: pad with zeros so that the checksum byte lands at
        // (next multiple of 16) - 1, i.e. the final image size is a multiple of 16.
        runningSize += 1  // reserve for checksum
        while (runningSize and 15 != 0) {
            out.write(0)
            runningSize += 1
        }
        out.write(checksum and 0xFF)

        if (padTo > 0) {
            if (out.size() > padTo) throw IllegalStateException(
                "image for ${elf.name} overflowed its pad window: ${out.size()} > $padTo bytes"
            )
            while (out.size() < padTo) out.write(0xAA)
        }
    }

    private data class Section(val name: String, val addr: Long, val data: ByteArray)
    private data class ParsedElf(val entry: Long, val sections: Map<String, Section>)

    /**
     * Minimal ELF32-LE reader: walks the section header table, keeps PROGBITS sections
     * that are loaded (addr != 0) and non-empty, and returns a {name → section} map.
     * ELF64 and big-endian files are rejected — the xtensa-lx106 toolchain only ever
     * emits ELF32 LE so a fuller reader would be dead code.
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

            val entry = bb.getInt(24).toLong() and 0xFFFFFFFFL
            val shoff = bb.getInt(32).toLong() and 0xFFFFFFFFL
            val shentsize = bb.getShort(46).toInt() and 0xFFFF
            val shnum = bb.getShort(48).toInt() and 0xFFFF
            val shstrndx = bb.getShort(50).toInt() and 0xFFFF
            if (shentsize != 0x28) throw IllegalStateException(
                "${file.name}: unexpected section header entry size 0x${shentsize.toString(16)}"
            )

            raf.seek(shoff)
            val shBytes = ByteArray(shnum * shentsize)
            raf.readFully(shBytes)
            val shb = ByteBuffer.wrap(shBytes).order(ByteOrder.LITTLE_ENDIAN)

            data class RawHdr(val nameOff: Int, val type: Int, val addr: Long, val offset: Long, val size: Long)
            val hdrs = (0 until shnum).map { i ->
                val base = i * shentsize
                RawHdr(
                    nameOff = shb.getInt(base),
                    type = shb.getInt(base + 4),
                    addr = shb.getInt(base + 12).toLong() and 0xFFFFFFFFL,
                    offset = shb.getInt(base + 16).toLong() and 0xFFFFFFFFL,
                    size = shb.getInt(base + 20).toLong() and 0xFFFFFFFFL,
                )
            }

            val strtabHdr = hdrs[shstrndx]
            raf.seek(strtabHdr.offset)
            val strBytes = ByteArray(strtabHdr.size.toInt())
            raf.readFully(strBytes)
            fun readName(off: Int): String {
                var end = off
                while (end < strBytes.size && strBytes[end] != 0.toByte()) end++
                return String(strBytes, off, end - off, Charsets.US_ASCII)
            }

            val sections = mutableMapOf<String, Section>()
            for (h in hdrs) {
                if (h.type != 0x01) continue          // SHT_PROGBITS only
                if (h.addr == 0L || h.size == 0L) continue
                val name = readName(h.nameOff)
                raf.seek(h.offset)
                val data = ByteArray(h.size.toInt())
                raf.readFully(data)
                sections[name] = Section(name, h.addr, data)
            }
            return ParsedElf(entry, sections)
        }
    }

    private fun writeU32Le(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun storeU32Le(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /**
     * Patches __crc_len / __crc_val fields at the start of the user app's .irom0.text
     * data. Position is deterministic: eboot occupies 4 KB, then 8 bytes of image_v1
     * header, then 8 bytes of segment header — so the first dword of .irom0.text
     * data sits at offset 4096 + 16. The LD script reserves two LONG(0) slots there
     * for us to overwrite.
     */
    private fun patchCrc(bytes: ByteArray) {
        storeU32Le(bytes, CRC_SIZE_OFFSET, 0)
        storeU32Le(bytes, CRC_VAL_OFFSET, 0)
        val crc = crc8266(bytes)
        storeU32Le(bytes, CRC_SIZE_OFFSET, bytes.size.toLong())
        storeU32Le(bytes, CRC_VAL_OFFSET, crc)
    }

    /**
     * MSB-first CRC32 variant used by eboot — matches `crc8266` in elf2bin.py.
     * Polynomial 0x04C11DB7, init 0xFFFFFFFF, no final xor; bit-at-a-time.
     */
    private fun crc8266(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            for (bitMask in intArrayOf(0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01)) {
                var bit = crc and 0x80000000L
                if ((b and bitMask) != 0) {
                    bit = if (bit == 0L) 1L else 0L
                }
                crc = (crc shl 1) and 0xFFFFFFFFL
                if (bit != 0L) crc = (crc xor 0x04C11DB7L) and 0xFFFFFFFFL
            }
        }
        return crc
    }
}

package com.osakaresearch.spark.compile

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pulls function symbols out of an ELF32-LE so a crash address from the UART
 * log (e.g. `epc1=0x402020ec`) can be mapped back to the sketch/core function
 * that owns it — the on-device equivalent of `xtensa-lx106-elf-addr2line`.
 *
 * Deliberately independent of [Elf2Image]: that reader walks PROGBITS
 * sections to assemble the flash image; this one walks SYMTAB/STRTAB and
 * keeps only [STT_FUNC] entries with a non-zero size. Keeping them separate
 * means neither reader has to grow a bag of "optional" outputs.
 */
object ElfInspect {
    data class FuncSymbol(val name: String, val addr: Long, val size: Long)

    private const val SHT_SYMTAB = 2
    private const val SHT_STRTAB = 3
    private const val STT_FUNC = 2
    private const val ELF32_SYM_SIZE = 16

    /** Returns every function-typed symbol with a size > 0, sorted by address. */
    fun functionSymbols(elf: File): List<FuncSymbol> {
        RandomAccessFile(elf, "r").use { raf ->
            val ehdr = ByteArray(0x34)
            raf.readFully(ehdr)
            val h = ByteBuffer.wrap(ehdr).order(ByteOrder.LITTLE_ENDIAN)
            require(h.get(0).toInt() == 0x7F && h.get(1).toInt().toChar() == 'E') {
                "${elf.name}: missing ELF magic"
            }
            val shoff = h.getInt(32).toLong() and 0xFFFFFFFFL
            val shentsize = h.getShort(46).toInt() and 0xFFFF
            val shnum = h.getShort(48).toInt() and 0xFFFF

            raf.seek(shoff)
            val shBytes = ByteArray(shnum * shentsize)
            raf.readFully(shBytes)
            val sh = ByteBuffer.wrap(shBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Locate .symtab (SHT_SYMTAB) and its linked string table.
            var symOff = 0L; var symSize = 0L; var symLink = 0
            for (i in 0 until shnum) {
                val base = i * shentsize
                if (sh.getInt(base + 4) == SHT_SYMTAB) {
                    symOff = sh.getInt(base + 16).toLong() and 0xFFFFFFFFL
                    symSize = sh.getInt(base + 20).toLong() and 0xFFFFFFFFL
                    symLink = sh.getInt(base + 40)
                    break
                }
            }
            if (symSize == 0L) return emptyList()

            // Read linked strtab.
            val strBase = symLink * shentsize
            require(sh.getInt(strBase + 4) == SHT_STRTAB) {
                "${elf.name}: .symtab link does not point at a strtab"
            }
            val strOff = sh.getInt(strBase + 16).toLong() and 0xFFFFFFFFL
            val strSize = sh.getInt(strBase + 20).toLong() and 0xFFFFFFFFL
            raf.seek(strOff)
            val strBytes = ByteArray(strSize.toInt())
            raf.readFully(strBytes)

            raf.seek(symOff)
            val symBytes = ByteArray(symSize.toInt())
            raf.readFully(symBytes)
            val sb = ByteBuffer.wrap(symBytes).order(ByteOrder.LITTLE_ENDIAN)

            val out = mutableListOf<FuncSymbol>()
            val count = (symSize / ELF32_SYM_SIZE).toInt()
            for (i in 0 until count) {
                val base = i * ELF32_SYM_SIZE
                val info = sb.get(base + 12).toInt() and 0xFF
                if ((info and 0xF) != STT_FUNC) continue
                val value = sb.getInt(base + 4).toLong() and 0xFFFFFFFFL
                val size = sb.getInt(base + 8).toLong() and 0xFFFFFFFFL
                if (size == 0L) continue
                val nameOff = sb.getInt(base)
                out.add(FuncSymbol(readName(strBytes, nameOff), value, size))
            }
            return out.sortedBy { it.addr }
        }
    }

    /**
     * Returns the function whose range [addr, addr+size) contains [pc], or
     * null if [pc] falls in a gap (e.g. inside raw assembly, the exception
     * vectors, or a runtime literal pool).
     */
    fun functionContaining(symbols: List<FuncSymbol>, pc: Long): FuncSymbol? =
        symbols.firstOrNull { pc >= it.addr && pc < it.addr + it.size }

    /** Linear name lookup — fine for the handful of names we care about. */
    fun find(symbols: List<FuncSymbol>, name: String): FuncSymbol? =
        symbols.firstOrNull { it.name == name }

    private fun readName(strTab: ByteArray, off: Int): String {
        if (off < 0 || off >= strTab.size) return ""
        var end = off
        while (end < strTab.size && strTab[end] != 0.toByte()) end++
        return String(strTab, off, end - off, Charsets.US_ASCII)
    }
}

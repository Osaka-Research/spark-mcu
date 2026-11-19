#!/usr/bin/env python3
"""Compare Spark's compiled Kotlin image writer with esptool 5.x.

Requires a JDK, Kotlin stdlib, esptool, and :app:compileDebugKotlin output.
Run: python3 tools/check_esp32_image.py [path/to/real/sketch.elf ...]
Synthetic ELFs exercise section merging, RAM splitting, padding and BSS.
Optional real sketch ELFs use the same checks. No board or USB access occurs.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

# esptool imports pySerial even for offline images; Android is Linux here.
if sys.platform == "android":
    sys.platform = "linux"

from esptool.bin_image import ESP32S3FirmwareImage
from esptool.cmds import elf2image


def fixture(rodata_size, ram_size, irom_address):
    appdesc = bytearray(256)
    struct.pack_into("<I", appdesc, 0, 0xABCD5432)
    appdesc[16:20] = b"test"
    sections = [
        (".dram0.data", 0x3FC90000, bytes([0xA5]) * ram_size, 1),
        (".dram0.bss", 0x3FCD0000, b"", 8),
        (".iram0.vectors", 0x40374000, bytes(range(16)), 1),
        (".iram0.text", 0x40374010, bytes([0x42]) * 1024, 1),
        (".flash.text", irom_address, bytes([0x93]) * 128, 1),
        (".flash.appdesc", 0x3C000020, bytes(appdesc), 1),
        (".flash.rodata", 0x3C000120, bytes([0x59]) * rodata_size, 1),
        (".debug_info", 0, b"must not enter the firmware image", 1),
    ]
    names = bytearray(b"\0")
    name_offsets = {}
    for name, *_ in sections + [(".shstrtab", 0, b"", 3)]:
        name_offsets[name] = len(names)
        names.extend(name.encode() + b"\0")
    sections.append((".shstrtab", 0, bytes(names), 3))
    elf = bytearray(52)
    headers = bytearray(40)  # null section
    for name, addr, data, kind in sections:
        offset = len(elf)
        elf.extend(data)
        elf.extend(b"\0" * (-len(elf) % 4))
        size = 1024 if kind == 8 else len(data)
        headers.extend(struct.pack("<10I", name_offsets[name], kind,
                                   3 if addr else 0, addr, offset, size, 0, 0, 4, 0))
    shoff = len(elf)
    elf.extend(headers)
    ident = b"\x7fELF\x01\x01\x01" + b"\0" * 9
    elf[:52] = struct.pack("<16sHHIIIIIHHHHHH", ident, 2, 94, 1, 0x40374000,
                           0, shoff, 0, 52, 32, 0, 40, len(sections) + 1, len(sections))
    return bytes(elf)


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", nargs="*", type=Path)
    parser.add_argument("--classes", type=Path, default=repo / "app/build/tmp/kotlin-classes/debug")
    parser.add_argument("--kotlin-stdlib", type=Path)
    args = parser.parse_args()
    stdlib = args.kotlin_stdlib
    if stdlib is None:
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"
        candidates = sorted(cache.glob("*/*/kotlin-stdlib-*.jar"))
        if not candidates:
            parser.error("Pass --kotlin-stdlib with the Kotlin standard library jar")
        stdlib = candidates[-1]
    with tempfile.TemporaryDirectory(prefix="spark-image-check-") as directory:
        work = Path(directory)
        harness = work / "CheckImage.java"
        harness.write_text('''
import java.io.File;
import java.nio.file.Files;
import com.osakaresearch.spark.compile.Elf2ImageEsp32;
public class CheckImage {
    public static void main(String[] args) throws Exception {
        byte[] image = Elf2ImageEsp32.INSTANCE.buildApp(new File(args[0]), 9,
            new Elf2ImageEsp32.FlashParams(2, 4, 80), 0, 65535);
        Files.write(new File(args[1]).toPath(), image);
    }
}
''')
        classpath = os.pathsep.join(str(p.resolve()) for p in (args.classes, stdlib, work))
        subprocess.run(["javac", "-cp", classpath, str(harness)], check=True)
        inputs = []
        # No DRAM, exhausted RAM, a split RAM segment, and multi-page DROM.
        for number, parameters in enumerate([(17, 0, 0x42000020),
                                              (16, 512, 0x42001000),
                                              (16, 70000, 0x42000020),
                                              (70000, 2048, 0x42000020)]):
            path = work / f"fixture-{number}.elf"
            path.write_bytes(fixture(*parameters))
            inputs.append(path)
        inputs.extend(p.resolve() for p in args.elf)
        for elf in inputs:
            expected = elf2image(str(elf), "esp32s3", flash_mode="dio", flash_size="4MB", flash_freq="80m")
            output = work / "spark.bin"
            subprocess.run(["java", "-cp", classpath, "CheckImage", str(elf), str(output)], check=True)
            actual = output.read_bytes()
            assert actual == expected, f"{elf.name}: Spark image differs from esptool"
            image = ESP32S3FirmwareImage(io.BytesIO(actual))
            assert image.checksum == image.calculate_checksum()
            assert image.stored_digest == image.calc_digest
            assert struct.unpack_from("<I", actual, 0x20)[0] == 0xABCD5432
            assert actual[0xB0:0xD0] == hashlib.sha256(elf.read_bytes()).digest()
            for segment in image.segments:
                if image.is_flash_addr(segment.addr):
                    assert (segment.file_offs + 8) % 65536 == segment.addr % 65536
            print(f"PASS {elf.name}: {len(actual)} bytes identical to esptool")


if __name__ == "__main__":
    main()

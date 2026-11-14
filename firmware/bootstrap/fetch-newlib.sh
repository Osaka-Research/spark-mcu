#!/usr/bin/env bash
# Pull newlib libc headers + gcc intrinsics from the esp-quick-toolchain
# release used by esp8266-arduino 3.1.2 (gcc 10.3.0, tag 3.1.0-gcc10.3).
#
# Why: ArduinoDroid's APK ships only compiler binaries. The esp8266-arduino
# source package's tools/sdk/ has ESP SDK headers + libc/libgcc *archives*
# but no libc *headers* (<stdlib.h>, <stdio.h>, <string.h>, ...) and no
# gcc intrinsics (<stddef.h>, <stdarg.h>, ...). This script fills that gap
# by downloading the matching esp-quick-toolchain tarball and copying just
# the header subtrees into our asset sysroot.
#
# Target headers/libs are host-independent, so any host variant of the
# tarball works. We pick aarch64 since the dev host here is arm64.
#
# Idempotent: skips the download if the tarball is already cached, and
# cp -n avoids clobbering ESP SDK headers that live in the same include/ dir.

set -euo pipefail
cd "$(dirname "$0")"

RELEASE="3.1.0-gcc10.3"
ASSET="aarch64-linux-gnu.xtensa-lx106-elf-e5f9fec.220621.tar.gz"
URL="https://github.com/earlephilhower/esp-quick-toolchain/releases/download/${RELEASE}/${ASSET}"
GCC_VER="10.3.0"

WORK=".work/newlib"
CACHE="${WORK}/${ASSET}"
STAGE="${WORK}/stage"
DST="../../app/src/main/assets/toolchain/xtensa-lx106-elf"

mkdir -p "$WORK"
if [[ ! -s "$CACHE" ]]; then
    echo "Fetching $URL"
    curl -sS -L -o "${CACHE}.part" "$URL"
    mv "${CACHE}.part" "$CACHE"
fi
echo "  cached $(du -h "$CACHE" | cut -f1)"

rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$CACHE" -C "$STAGE" \
    "xtensa-lx106-elf/include" \
    "xtensa-lx106-elf/xtensa-lx106-elf/include" \
    "xtensa-lx106-elf/lib/gcc/xtensa-lx106-elf/${GCC_VER}/include" \
    "xtensa-lx106-elf/lib/gcc/xtensa-lx106-elf/${GCC_VER}/include-fixed" \
    "xtensa-lx106-elf/lib/gcc/xtensa-lx106-elf/${GCC_VER}/libgcc.a" \
    "xtensa-lx106-elf/xtensa-lx106-elf/lib/libm.a" \
    "xtensa-lx106-elf/xtensa-lx106-elf/lib/libstdc++.a"

# libc.a and libg.a are hard-linked to each other in the tarball; tar fails
# to recreate the link on Android's /data, so pipe each one out separately.
for lib in libc.a libg.a; do
    tar -xzOf "$CACHE" "xtensa-lx106-elf/xtensa-lx106-elf/lib/$lib" \
        > "${STAGE}/xtensa-lx106-elf/xtensa-lx106-elf/lib/$lib"
done

SRC="${STAGE}/xtensa-lx106-elf"
mkdir -p "${DST}/lib/gcc/xtensa-lx106-elf/${GCC_VER}"
# Target-side newlib + C++ stdlib headers
cp -an "${SRC}/xtensa-lx106-elf/include/." "${DST}/include/"
# Toolchain-prefix xtensa/*.h + gdb/*.h (coreasm.h, cacheasm.h, hal.h, ...).
# Used by hand-written .S files in the Arduino core; -n to avoid clobbering
# the target-side core-isa.h that already landed above.
cp -an "${SRC}/include/." "${DST}/include/"
cp -a  "${SRC}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/include"       "${DST}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/"
cp -a  "${SRC}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/include-fixed" "${DST}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/"
cp -a  "${SRC}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/libgcc.a"      "${DST}/lib/gcc/xtensa-lx106-elf/${GCC_VER}/"

# Runtime archives — don't clobber ESP SDK libs that live in the same dir.
cp -an "${SRC}/xtensa-lx106-elf/lib/libc.a"      "${DST}/lib/"
cp -an "${SRC}/xtensa-lx106-elf/lib/libg.a"      "${DST}/lib/"
cp -an "${SRC}/xtensa-lx106-elf/lib/libm.a"      "${DST}/lib/"
cp -an "${SRC}/xtensa-lx106-elf/lib/libstdc++.a" "${DST}/lib/"

echo ""
echo "merged:"
echo "  $(find "${DST}/include" -maxdepth 1 -name '*.h' | wc -l) libc+SDK headers"
echo "  $(find "${DST}/include/c++/${GCC_VER}" -maxdepth 1 -type f | wc -l) C++ stdlib headers"
echo "  $(find "${DST}/lib/gcc/xtensa-lx106-elf/${GCC_VER}" -name '*.h' | wc -l) gcc intrinsics"

#!/usr/bin/env bash
# Native bootstrap and target runtime for Arduino-ESP32 3.0.5 (esp-xs3@2302).
# Target libraries are host independent. Use Espressif's exact release so
# newlib's reentrancy layout, locking, long calls and exception unwinding
# match the precompiled ESP-IDF SDK and Arduino core shipped in the app.
set -euo pipefail

: "${PHASE_A_PREFIX:?PHASE_A_PREFIX must be set}"

TARGET="xtensa-esp32s3-elf"
RELEASE="esp-12.2.0_20230208"
ARCHIVE="xtensa-esp32s3-elf-12.2.0_20230208-x86_64-linux-gnu.tar.gz"
SHA256="61495ffe575e00c6998ae7274ff917658c04bded62ece0937c7042d6dcbf46de"
WORK="$HOME/xtensa-s3-phase-a"
mkdir -p "$WORK" "$PHASE_A_PREFIX"
curl --fail --location --retry 3 \
  "https://github.com/espressif/crosstool-NG/releases/download/$RELEASE/$ARCHIVE" \
  --output "$WORK/$ARCHIVE"
printf '%s  %s\n' "$SHA256" "$WORK/$ARCHIVE" | sha256sum --check --status
tar -xzf "$WORK/$ARCHIVE" --strip-components=1 -C "$PHASE_A_PREFIX"
test "$("$PHASE_A_PREFIX/bin/$TARGET-gcc" -dumpfullversion)" = "12.2.0"

# These ESP-IDF entry points are absent from differently configured newlib
# or libgcc builds even when those builds use a compatible instruction set.
"$PHASE_A_PREFIX/bin/$TARGET-nm" --defined-only \
  "$PHASE_A_PREFIX/$TARGET/lib/libc.a" > "$WORK/libc-symbols.txt"
"$PHASE_A_PREFIX/bin/$TARGET-nm" --defined-only \
  "$PHASE_A_PREFIX/lib/gcc/$TARGET/12.2.0/libgcc.a" > "$WORK/libgcc-symbols.txt"
grep -q ' _cleanup_r$' "$WORK/libc-symbols.txt"
grep -q ' _Unwind_SetEnableExceptionFdeSorting$' "$WORK/libgcc-symbols.txt"
echo "Verified Espressif $RELEASE compiler and target runtime"

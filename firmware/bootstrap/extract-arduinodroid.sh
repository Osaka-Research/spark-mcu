#!/usr/bin/env bash
# Bootstrap-only — NOT for shipping.
#
# Two-source bootstrap:
#   1. Pull Bionic-hosted xtensa-lx106-elf binaries out of an ArduinoDroid
#      APK or XAPK (their lib/arm64-v8a/libesp8266_*.so files).
#   2. Pull the open-source ESP8266 Arduino package from GitHub — that's
#      where the sysroot (headers + libs) and Arduino core live. ArduinoDroid
#      doesn't bundle these; it downloads them at first launch.
#
# Output tarball shape matches what the Canadian-cross workflow will emit:
#     bin/                       — unprefixed xtensa-lx106-elf-* + cc1/as/ld/...
#     xtensa-lx106-elf/include/  — SDK + newlib headers (from upstream)
#     xtensa-lx106-elf/lib/      — SDK + newlib libs (from upstream)
#     arduino-core-esp8266/      — cores/esp8266/, variants/, libraries/
#     BOOTSTRAP_SOURCE.txt       — provenance
#
# Policy (memory/project_spark_toolchain_policy.md):
# Output never ships. When Canadian cross matures, flip the gradle release
# tag and delete the bootstrap tarball.
#
# Usage:
#   ./extract-arduinodroid.sh /path/to/ArduinoDroid.{apk,xapk} [--dry-run]

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 /path/to/ArduinoDroid.{apk,xapk} [--dry-run]" >&2
    exit 2
fi

APK="$1"
DRY_RUN=false
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=true

if [[ ! -f "$APK" ]]; then
    echo "ERROR: file not found: $APK" >&2
    exit 1
fi

# Pinned upstream Arduino-ESP8266 release. The Canadian cross in phase 1
# builds against these same source trees, so the ABI matches.
ARDUINO_ESP8266_VER="${ARDUINO_ESP8266_VER:-3.1.2}"
ARDUINO_ESP8266_URL="https://github.com/esp8266/Arduino/releases/download/${ARDUINO_ESP8266_VER}/esp8266-${ARDUINO_ESP8266_VER}.zip"

# --- Paths -------------------------------------------------------------------
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/.work"
OUT="$HERE/out"
STAGE="$WORK/stage"
UNZIP="$WORK/apk"
ESP_PKG="$WORK/esp8266-pkg"

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT" "$STAGE/bin" "$STAGE/xtensa-lx106-elf" "$UNZIP" "$ESP_PKG"

# --- Provenance of input -----------------------------------------------------
APK_SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK")
APK_SHA=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$APK" | cut -d' ' -f1)
echo "Input: $APK"
echo "  size:   $APK_SIZE bytes"
echo "  sha256: $APK_SHA"
echo ""

# --- Identify APK / XAPK and locate arm64 split -----------------------------
case "${APK,,}" in
    *.xapk|*.apks|*.apkm)
        echo "Detected split-APK bundle. Unpacking ..."
        unzip -o -q "$APK" -d "$UNZIP" || true   # ApkPure zips can trip CRC checks; files still land
        ARM64_APK=$(find "$UNZIP" -maxdepth 1 -iname "config.arm64_v8a.apk" | head -1)
        BASE_APK=$(find "$UNZIP" -maxdepth 1 -iname "*.apk" ! -iname "config.*" | head -1)
        if [[ -z "$ARM64_APK" ]]; then
            echo "ERROR: no config.arm64_v8a.apk inside bundle." >&2
            ls "$UNZIP" >&2
            exit 3
        fi
        echo "  arm64 split: $(basename "$ARM64_APK") ($(stat -c%s "$ARM64_APK") bytes)"
        [[ -n "$BASE_APK" ]] && echo "  base apk:    $(basename "$BASE_APK")"
        ;;
    *.apk)
        echo "Detected flat APK."
        ARM64_APK="$APK"
        BASE_APK="$APK"
        ;;
    *)
        echo "ERROR: unknown extension. Expected .apk / .xapk / .apks / .apkm" >&2
        exit 2
        ;;
esac

# --- Inventory lib/arm64-v8a -------------------------------------------------
ARM64_UNZIP="$WORK/arm64"
mkdir -p "$ARM64_UNZIP"
unzip -o -q "$ARM64_APK" "lib/arm64-v8a/*" -d "$ARM64_UNZIP" || true

echo ""
echo "=== libesp8266_*.so in arm64 split ==="
ls "$ARM64_UNZIP/lib/arm64-v8a/" 2>/dev/null | grep -E '^libesp8266_' | head -40 || {
    echo "WARN: no libesp8266_*.so entries found" >&2
    ls "$ARM64_UNZIP/lib/arm64-v8a/" 2>/dev/null | head -20 >&2
}

if [[ "$DRY_RUN" == true ]]; then
    echo ""
    echo "--dry-run: inventory only. Re-run without --dry-run to extract + fetch."
    exit 0
fi

# --- Extract ESP8266 toolchain binaries --------------------------------------
# ArduinoDroid names them libesp8266_<realname>.so so multiple chip toolchains
# can co-exist in one lib dir. Strip the prefix and suffix; land them in bin/.
echo ""
echo "Extracting ESP8266 toolchain binaries ..."
BIN_COUNT=0
shopt -s nullglob
for so in "$ARM64_UNZIP/lib/arm64-v8a/"libesp8266_*.so; do
    base=$(basename "$so" .so)          # libesp8266_xtensa-lx106-elf-gcc
    real="${base#libesp8266_}"          # xtensa-lx106-elf-gcc
    cp "$so" "$STAGE/bin/$real"
    chmod +x "$STAGE/bin/$real"
    BIN_COUNT=$((BIN_COUNT + 1))
done
if [[ $BIN_COUNT -eq 0 ]]; then
    echo "ERROR: zero toolchain binaries extracted." >&2
    exit 4
fi
echo "  wrote $BIN_COUNT binaries to bin/"

# --- Fetch Arduino-ESP8266 release -------------------------------------------
echo ""
echo "Fetching Arduino-ESP8266 ${ARDUINO_ESP8266_VER} from GitHub ..."
ZIP="$ESP_PKG/esp8266-${ARDUINO_ESP8266_VER}.zip"
if command -v curl >/dev/null 2>&1; then
    curl -L --fail --silent --show-error -o "$ZIP" "$ARDUINO_ESP8266_URL"
elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$ZIP" "$ARDUINO_ESP8266_URL"
else
    echo "ERROR: need curl or wget to fetch Arduino-ESP8266" >&2
    exit 5
fi
echo "  downloaded $(stat -c%s "$ZIP" 2>/dev/null || stat -f%z "$ZIP") bytes"

unzip -o -q "$ZIP" -d "$ESP_PKG"
ESP_ROOT=$(find "$ESP_PKG" -maxdepth 2 -type d -name "esp8266-${ARDUINO_ESP8266_VER}" | head -1)
[[ -z "$ESP_ROOT" ]] && ESP_ROOT=$(find "$ESP_PKG" -maxdepth 2 -type d -name "esp8266*" | head -1)
if [[ -z "$ESP_ROOT" || ! -d "$ESP_ROOT/tools/sdk" ]]; then
    echo "ERROR: unexpected Arduino-ESP8266 layout after unzip" >&2
    find "$ESP_PKG" -maxdepth 3 -type d | head -40 >&2
    exit 6
fi
echo "  unpacked to $ESP_ROOT"

# --- Stage sysroot -----------------------------------------------------------
# Arduino-ESP8266 ships the newlib+SDK sysroot under tools/sdk/.
# Layout we want in the output tarball (matches a conventional cross prefix):
#   xtensa-lx106-elf/include/  ← tools/sdk/include + tools/sdk/libc/xtensa-lx106-elf/include
#   xtensa-lx106-elf/lib/      ← tools/sdk/lib + tools/sdk/libc/xtensa-lx106-elf/lib + tools/sdk/ld
echo ""
echo "Staging sysroot ..."
mkdir -p "$STAGE/xtensa-lx106-elf/include" "$STAGE/xtensa-lx106-elf/lib"

# SDK headers (Espressif NONOS SDK)
cp -a "$ESP_ROOT/tools/sdk/include/." "$STAGE/xtensa-lx106-elf/include/" 2>/dev/null || true

# newlib headers (bundled under libc/xtensa-lx106-elf/include in recent cores)
if [[ -d "$ESP_ROOT/tools/sdk/libc/xtensa-lx106-elf/include" ]]; then
    cp -a "$ESP_ROOT/tools/sdk/libc/xtensa-lx106-elf/include/." "$STAGE/xtensa-lx106-elf/include/"
fi

# lwip2 / ssl / uzlib — sit alongside include/ under tools/sdk and are
# selected via boards.txt build.lwip_include (default for ESP8266 3.x is
# lwip2/include). Keep the trees intact so our -I path matches upstream.
for tree in lwip2 ssl uzlib; do
    if [[ -d "$ESP_ROOT/tools/sdk/$tree" ]]; then
        mkdir -p "$STAGE/xtensa-lx106-elf/$tree"
        cp -a "$ESP_ROOT/tools/sdk/$tree/." "$STAGE/xtensa-lx106-elf/$tree/"
    fi
done

# SDK libs
cp -a "$ESP_ROOT/tools/sdk/lib/." "$STAGE/xtensa-lx106-elf/lib/" 2>/dev/null || true

# newlib libs
if [[ -d "$ESP_ROOT/tools/sdk/libc/xtensa-lx106-elf/lib" ]]; then
    cp -a "$ESP_ROOT/tools/sdk/libc/xtensa-lx106-elf/lib/." "$STAGE/xtensa-lx106-elf/lib/"
fi

# linker scripts
if [[ -d "$ESP_ROOT/tools/sdk/ld" ]]; then
    mkdir -p "$STAGE/xtensa-lx106-elf/lib/ld"
    cp -a "$ESP_ROOT/tools/sdk/ld/." "$STAGE/xtensa-lx106-elf/lib/ld/"
fi

echo "  include: $(find "$STAGE/xtensa-lx106-elf/include" -name '*.h' 2>/dev/null | wc -l) headers"
echo "  lib:     $(find "$STAGE/xtensa-lx106-elf/lib" -name '*.a' 2>/dev/null | wc -l) static archives"

# --- Stage Arduino core ------------------------------------------------------
echo ""
echo "Staging Arduino core ..."
mkdir -p "$STAGE/arduino-core-esp8266"
for sub in cores/esp8266 variants libraries bootloaders boards.txt platform.txt; do
    if [[ -e "$ESP_ROOT/$sub" ]]; then
        mkdir -p "$STAGE/arduino-core-esp8266/$(dirname "$sub")"
        cp -a "$ESP_ROOT/$sub" "$STAGE/arduino-core-esp8266/$sub"
    fi
done
echo "  staged $(du -sh "$STAGE/arduino-core-esp8266" | cut -f1) of core sources"

# --- Provenance file ---------------------------------------------------------
cat > "$STAGE/BOOTSTRAP_SOURCE.txt" <<EOF
This tarball was assembled by firmware/bootstrap/extract-arduinodroid.sh
for dev-time validation only. Per Spark policy
(memory/project_spark_toolchain_policy.md) it must not ship to users.

Toolchain binaries extracted from ArduinoDroid:
  source: $(basename "$APK")
  size:   $APK_SIZE bytes
  sha256: $APK_SHA
  date:   $(date -u +%Y-%m-%dT%H:%M:%SZ)
  count:  $BIN_COUNT binaries (libesp8266_*.so, prefix stripped)

Sysroot + Arduino core fetched from upstream:
  source: $ARDUINO_ESP8266_URL
  version: $ARDUINO_ESP8266_VER

Replace with Canadian-cross build output before release.
EOF

# --- Tarball -----------------------------------------------------------------
TARBALL="$OUT/xtensa-lx106-elf-android-arm64.tar.xz"
echo ""
echo "Creating tarball: $TARBALL"
tar -cJf "$TARBALL" -C "$STAGE" .
echo "  size: $(du -h "$TARBALL" | cut -f1)"
echo "  sha256: $(sha256sum "$TARBALL" | cut -d' ' -f1)"
echo ""
echo "Done."
echo ""
echo "Next: upload $TARBALL to a GitHub release:"
echo "  gh release create toolchain-bootstrap-$(date +%Y%m%d) \\"
echo "    --title 'Bootstrap toolchain (ArduinoDroid-derived)' \\"
echo "    --notes 'Dev-only — see BOOTSTRAP_SOURCE.txt' \\"
echo "    '$TARBALL'"
echo "Then set spark.toolchain.releaseTag in gradle.properties to that tag."

#!/usr/bin/env bash
# Stage the Arduino-ESP32 3.0.5 S3 core and SDK beside the Android-hosted
# compiler. The core archive is built once on the CI runner with the matching
# Espressif GCC release; the app only compiles sketches and links them.
set -euo pipefail

: "${PREFIX:?PREFIX must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"

mkdir -p "$HOME/.local/bin" "$RUNNER_TEMP/spark-s3-blink"
curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh \
  | BINDIR="$HOME/.local/bin" sh
export PATH="$HOME/.local/bin:$PATH"

arduino-cli config init --overwrite
arduino-cli config set board_manager.additional_urls \
  "https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json"
arduino-cli core update-index
arduino-cli core install esp32:esp32@3.0.5

sketch="$RUNNER_TEMP/spark-s3-blink/Blink"
mkdir -p "$sketch"
cat > "$sketch/Blink.ino" <<'EOF'
void setup() { pinMode(2, OUTPUT); }
void loop() { digitalWrite(2, HIGH); delay(500); digitalWrite(2, LOW); delay(500); }
EOF
build="$RUNNER_TEMP/spark-s3-blink/build"
arduino-cli compile --fqbn esp32:esp32:esp32s3 --build-path "$build" "$sketch"

hardware="$HOME/.arduino15/packages/esp32/hardware/esp32/3.0.5"
sdk=$(find "$HOME/.arduino15/packages/esp32/tools/esp32-arduino-libs" \
  -mindepth 2 -maxdepth 3 -type d -name esp32s3 -print -quit)
test -d "$hardware/cores/esp32"
test -d "$hardware/variants/esp32s3"
test -d "$sdk/flags"
test -f "$build/core/core.a"

out="$PREFIX/arduino-core-esp32"
mkdir -p "$out/cores" "$out/variants" "$out/prebuilt" "$out/tools/partitions"
cp -a "$hardware/cores/esp32" "$out/cores/"
cp -a "$hardware/variants/esp32s3" "$out/variants/"
cp -a "$hardware/libraries" "$out/"
cp -a "$sdk" "$out/sdk"
cp "$build/core/core.a" "$out/prebuilt/core.a"
cp "$build/Blink.ino.bootloader.bin" "$out/prebuilt/bootloader.bin"
cp "$build/Blink.ino.partitions.bin" "$out/prebuilt/partitions.bin"
cp "$hardware/tools/partitions/boot_app0.bin" "$out/prebuilt/boot_app0.bin"
printf 'arduino-esp32=3.0.5\nfqbn=esp32:esp32:esp32s3\n' > "$out/prebuilt/source.txt"
du -sh "$out"

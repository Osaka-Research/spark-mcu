#!/usr/bin/env bash
# Stage the Arduino-ESP32 3.0.5 S3 core and SDK beside the Android-hosted
# compiler. The core archive is built once on the CI runner with the matching
# Espressif GCC release; the app only compiles sketches and links them.
set -euo pipefail

: "${PREFIX:?PREFIX must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
: "${PHASE_A_PREFIX:?PHASE_A_PREFIX must be set}"

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

# Compile and link with the packaged runtime, not arduino-cli's private
# toolchain. This catches ABI/configuration mismatches before publishing.
target="xtensa-esp32s3-elf"
runtime="$PREFIX/$target"
gcc_lib="$PREFIX/lib/gcc/$target/12.2.0"
cxx="$runtime/include/c++/12.2.0"
compiler="$PHASE_A_PREFIX/bin/$target-g++"
cat > "$build/runtime-check.cpp" <<'EOF'
#include <Arduino.h>
#include <vector>
std::vector<int> samples;
void setup() { Serial.begin(115200); samples.push_back(1); }
void loop() { rgbLedWrite(48, 32, 0, 0); Serial.println(samples[0]); delay(100); }
EOF
"$compiler" "@$out/sdk/flags/cpp_flags" -Os \
  -DF_CPU=240000000L -DARDUINO=10819 -DARDUINO_ARCH_ESP32 -DESP32 \
  -DARDUINO_ESP32S3_DEV -DARDUINO_USB_MODE=1 -DARDUINO_USB_CDC_ON_BOOT=0 \
  -isystem "$cxx" -isystem "$cxx/$target" -isystem "$cxx/backward" \
  -isystem "$gcc_lib/include" -isystem "$runtime/include" \
  -I"$out/cores/esp32" -I"$out/variants/esp32s3" \
  "@$out/sdk/flags/defines" -iprefix "$out/sdk/include/" \
  "@$out/sdk/flags/includes" -I"$out/sdk/qio_qspi/include" \
  -c "$build/runtime-check.cpp" -o "$build/runtime-check.o"
"$compiler" -nostdlib "@$out/sdk/flags/ld_flags" "@$out/sdk/flags/ld_scripts" \
  -L"$out/sdk/lib" -L"$gcc_lib" -L"$runtime/lib" \
  -L"$out/sdk/ld" -L"$out/sdk/qio_qspi" \
  -Wl,--wrap=esp_panic_handler -Wl,--start-group \
  "$build/runtime-check.o" "$out/prebuilt/core.a" "@$out/sdk/flags/ld_libs" \
  -Wl,--end-group -Wl,-EL -o "$build/runtime-check.elf"
test -s "$build/runtime-check.elf"
echo "Packaged S3 headers, core and target runtime compiled and linked successfully"
du -sh "$out"

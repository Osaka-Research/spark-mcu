# spark-mcu

WiFi microcontroller operator kit. One repo. Two halves.

### `firmware/`
PlatformIO sketch for ESP32 and ESP8266/NodeMCU. WebSocket server on port 81. JSON command protocol. Motor, servo, GPIO, PWM out of the box. Built for the micro, not around it.

### `app/`
Android companion. Kotlin + Compose. Four tabs: **Scan**, **Control**, **Monitor**, **Firmware**. Finds the chip, drives it, prints what it says, and reflashes it when the sketch needs changing.

---

## App capabilities

**Scan.** mDNS discovery of `spark-mcu-xxxx.local` plus manual host entry.

**Control.** Motor, servo, GPIO, PWM, raw command send. Live state readback on the same socket.

**Monitor.** Streaming telemetry and log view. Event filters. Session save to Downloads.

**Firmware.** Full flash toolbox. Three independent paths:

- **WiFi OTA** — HTTP POST to the chip's ArduinoOTA endpoint. Works for any sketch that pulls in `ArduinoOTA.begin()`.
- **USB-OTG flash** — speaks the ESP ROM bootloader serial protocol (SYNC / FLASH_BEGIN / FLASH_DATA / FLASH_END, SLIP-framed). Supports CP2102 / CH340 external bridges and the ESP32-S3 native USB-JTAG-serial device (VID `0x303A`) — each uses its own DTR/RTS reset sequence. A **manual boot** switch is available for boards without an auto-reset circuit.
- **On-device compile** — compiles a `.ino` sketch on the phone using a bundled xtensa cross-toolchain. ESP8266 NodeMCU is the production target today; ESP32-S3 is in active scaffolding (toolchain CI landed, compile pipeline next). A multi-file sketch scan merges sibling `.ino` files and picks up `.cpp` / `.c` / `.S` / `src/` sources the Arduino-way. Libraries shipped with the core (ESP8266WiFi, Servo, SPI, Wire, …) are auto-resolved from `#include`. First build compiles `core.a` (~2–4 min); subsequent builds are cache hits.

The compile → flash loop is end-to-end: compile runs to `firmware.bin`, saves it to Downloads, auto-selects it in the flasher, and the USB tab flashes it with a tap.

**Precompiled blink flow (ESP32-S3).** For validating hardware before the on-device S3 compiler lands: pick a folder containing the four `arduino-cli compile` outputs (`bootloader.bin`, `partitions.bin`, `boot_app0.bin`, `firmware.bin`), and the app assembles a four-blob flash plan and runs it in a single ROM session.

---

## Protocol

JSON over WebSocket. Minimal, legible, no framing games.

```json
{"cmd":"motor","left":120,"right":-80}
{"cmd":"servo","pin":13,"angle":90}
{"cmd":"pin","pin":2,"mode":"out","value":1}
{"cmd":"read","pin":34}
{"cmd":"ping"}
```

Responses and telemetry stream back on the same socket:

```json
{"evt":"pong","uptime_ms":12438}
{"evt":"read","pin":34,"value":2047}
{"evt":"log","level":"info","msg":"motors ready"}
```

That is the entire interface. Add handlers in firmware, use them from the app.

## Toolchain

The xtensa cross-toolchains the app ships with (`xtensa-lx106-elf` for ESP8266, `xtensa-esp32s3-elf` for S3) are built in CI as Canadian-cross targets — build=x86_64 Linux, host=aarch64 Android, target=xtensa. Pins match the upstream Espressif releases (`esp-2021r2-patch5` / gcc 10.3 for lx106, `esp-13.2.0_20240530` / gcc 13.2 for S3). The resulting binaries ship inside the APK as `lib*.so` under `jniLibs/arm64-v8a/` so Android grants them an exec-permitted selinux context, then get symlinked to their unprefixed names at first app launch. See `firmware/toolchain/` for the build scripts and `.github/workflows/xtensa-toolchain.yml` for the job that produces the tarballs.

## Firmware build

```
cd firmware
pio run -e esp32dev          # or -e nodemcuv2
pio run -e esp32dev -t upload
```

## App build

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## First boot

The chip comes up as WiFi AP `spark-mcu-xxxx`. Connect, open `192.168.4.1`, enter home WiFi credentials, reboot. After that it registers on mDNS as `spark-mcu-xxxx.local`.

The app scans mDNS or takes a manual IP.

---

Osaka Research.

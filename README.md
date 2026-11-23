# Spark MCU: ESP8266 and ESP32-S3 controller and firmware flasher for Android

Spark MCU turns an Android phone into a portable workspace for ESP8266, ESP32, and ESP32-S3 projects. Discover a board on Wi-Fi, control its pins and connected hardware, view live logs, and install firmware over USB or Wi-Fi. The repository includes both the Android app and the companion microcontroller firmware.

## What can I build with it?

- **A phone-controlled robot:** send motor and servo commands from the Control tab and watch the board respond.
- **A sensor dashboard:** read GPIO values and stream events or logs to the Monitor tab.
- **A portable firmware workstation:** compile an Arduino sketch on a supported Android phone, then flash the resulting binary over USB-OTG.
- **An ESP32-S3 setup or recovery tool:** flash the bootloader, partition table, boot app image, and firmware together from a precompiled build.
- **An on-phone engineering agent:** describe a hardware task in the AI tab and let Spark identify the chip, edit code, compile, flash with authorization, and diagnose serial output.

For live control and telemetry, install the Spark MCU firmware on the board. The USB flasher can also install compatible precompiled ESP firmware without the Spark MCU firmware already running.

## Supported boards

| Board | Spark MCU firmware | USB connection | Compile on phone |
| --- | --- | --- | --- |
| ESP8266 NodeMCU (`nodemcuv2`) | Yes | CP2102 or CH340 USB serial | Yes, with the bundled LX106 toolchain |
| ESP32-S3 DevKitC-1 | Yes | Native USB serial/JTAG or supported USB serial bridge | Yes, when the optional S3 toolchain is included in the APK |
| ESP32 DevKit (`esp32dev`) | Yes | Supported USB serial bridge | No bundled on-phone compiler |

The app requires Android 9 (API 28) or newer. USB flashing also requires a phone or tablet with USB host support and a suitable cable or OTG adapter. The ESP32-S3 compiler bundle is optional because it substantially increases APK size.

## Get started

1. Build and install the Android APK: run `./gradlew assembleDebug` from the repository root. Find it at `app/build/outputs/apk/debug/app-debug.apk`.
2. Build the companion firmware with [PlatformIO](https://platformio.org/):

   ```sh
   cd firmware
   pio run -e nodemcuv2            # ESP8266 NodeMCU
   pio run -e esp32dev              # ESP32 DevKit
   pio run -e esp32-s3-devkitc-1    # ESP32-S3 DevKitC-1
   ```

   Upload the matching build with `pio run -e <environment> -t upload`. See the [firmware guide](firmware/README.md) for wiring and setup details.
3. On first boot, the board creates a Wi-Fi access point named `spark-mcu-xxxx`. Connect to it, open `http://192.168.4.1`, enter your Wi-Fi credentials, and reboot the board.
4. Put the phone on the same network. In the app, open **Scan** and select the discovered board, or enter its IP address manually. Use **Control** and **Monitor** to interact with it.

## App features

### Discover and control a board

**Scan** finds `_spark._tcp` devices through mDNS and also accepts a manual IP address. **Control** sends motor, servo, GPIO, PWM, and raw JSON commands. **Monitor** displays live telemetry and logs, supports filtering, and can save a session to Downloads.

### Install firmware

The **Firmware** tab supports three paths:

- **USB-OTG:** flash through the ESP ROM serial bootloader using a CP2102 or CH340 bridge, or the ESP32-S3 native USB serial/JTAG interface. A manual boot option covers boards without automatic reset.
- **Wi-Fi OTA:** send firmware to a running compatible board that has OTA support enabled.
- **On-phone compile:** build a `.ino` sketch with the included ESP8266 toolchain or an optional ESP32-S3 toolchain, save `firmware.bin` to Downloads, and select it for flashing.

For a precompiled ESP32-S3 build, select a folder containing `bootloader.bin`, `partitions.bin`, `boot_app0.bin`, and `firmware.bin`. Spark MCU flashes all four images in one session. The S3 on-phone compiler also uses the bundled board images when they are available.

### AI assistance and local automation

Connect ChatGPT in the **AI** tab and select an available model to use the board tools. Describe the intended behavior, or use **Build, run & debug** for the current project. Spark keeps a visible plan, working sketch, compiler diagnostics and recent observations across turns and app restarts. Other providers currently offer chat guidance only.

The agent can identify a USB-connected ESP8266 or ESP32-S3 through its ROM, write or precisely edit a complete Arduino sketch, compile it on the phone, and retain failed source for repair. With Spark MCU firmware already running over Wi-Fi, it can identify the board, sample analog pins, control documented pins, servos and motors, and upload matching builds through OTA. It checks fresh serial output after USB flashing and reconnects to ping after Wi-Fi OTA. USB adapter names do not identify the exact board, wiring or LED pin. Unsupported chips are not assigned a compatible-looking build target.

Flashing verifies the chip and retained binary. The first write shows the actual build and connected board for confirmation. **Allow corrected firmware for this task on this board connection** lets the agent flash repairs without asking again; the grant ends on Stop, a new task, changed project notes, app restart, or connection change. Serial evidence still needs to match the requested behavior; a successful write or silent monitor alone does not establish success.

Try: “Identify my board, write a sketch that prints a heartbeat every second, compile and flash it, then check the output and fix any errors.” Android USB permission dialogs, uncertain wiring, and any necessary physical button actions still need the user. A bounded task that pauses can resume with **Continue** using saved code and results.

The tool loop preserves Responses output items and message phases following [OpenAI conversation-state guidance](https://developers.openai.com/api/docs/guides/conversation-state). Inference uses the selected account model and the documented [ChatGPT plan flow](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).

While the app is running, local MCP clients can call the authenticated endpoint at `http://127.0.0.1:8847/mcp` to list USB devices, compile sketches, flash firmware, and monitor serial output. Copy or rotate its bearer token from the Scan tab. The endpoint listens on the phone's loopback address.

## WebSocket command examples

The companion firmware uses JSON over WebSocket on port `81`. Commands and replies share the same connection:

```json
{"cmd":"motor","left":120,"right":-80}
{"cmd":"servo","pin":13,"angle":90}
{"cmd":"pin","pin":2,"mode":"out","value":1}
{"cmd":"read","pin":34}
{"cmd":"ping"}
```

Example replies:

```json
{"evt":"pong","uptime_ms":12438}
{"evt":"read","pin":34,"value":2047}
{"evt":"log","level":"info","msg":"motors ready"}
```

## Build with ESP32-S3 compilation support

The APK always includes the ESP8266 toolchain. ESP32-S3 compilation is enabled when an Android arm64 `xtensa-esp32s3-elf-android-arm64.tar.xz` archive is available. Place it in `app/build/toolchain-cache/`, or set `spark.s3.toolchain.releaseTag` to a release containing that archive, then run `./gradlew assembleDebug`. The toolchain executables must be Android-compatible PIE binaries. See the [toolchain guide](firmware/toolchain/README.md) and [CI workflow](.github/workflows/xtensa-toolchain.yml) for how the archive is built.

## Agent checks

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug`. With compiler binaries already staged locally, add `-Pspark.toolchain.skipFetch=true`. Tests cover saved failed sketches, revision-safe edits, stale build rejection, compiler diagnostics, serial cursors, flash-grant attachment binding, and the sequential repair loop with simulated tool results. They also check cancellation, duplicate tool calls, malformed response arguments, and incomplete streams. Real chip detection, firmware execution and USB re-enumeration require an attached board and are not established by these JVM tests.

## Repository layout

| Path | Contents |
| --- | --- |
| `app/` | Kotlin and Jetpack Compose Android app |
| `firmware/` | Arduino and PlatformIO companion firmware |
| `firmware/toolchain/` | Android cross-toolchain and ESP32-S3 core packaging scripts |
| `.github/workflows/xtensa-toolchain.yml` | CI build for optional compiler bundles |

Spark MCU is developed by Osaka Research.

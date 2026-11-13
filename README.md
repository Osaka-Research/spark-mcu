# spark-mcu

WiFi microcontroller operator kit. One repo. Two halves.

### `firmware/`
PlatformIO sketch for ESP32 and ESP8266/NodeMCU. WebSocket server on port 81. JSON command protocol. Motor, servo, GPIO, PWM out of the box. Built for the micro, not around it.

### `app/`
Android companion. Kotlin + Compose. Three tabs: **Scan**, **Control**, **Monitor**. Finds the chip, drives it, prints what it says.

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

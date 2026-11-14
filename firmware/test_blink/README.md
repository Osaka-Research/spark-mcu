# test_blink

Minimal sketch for verifying the USB flasher path in the Spark Android app. Blinks the onboard LED at 1 Hz and prints `tick` over serial at 115200 baud.

## Build

```
pio run -e esp32dev          # ESP32 DevKit — LED on GPIO2
pio run -e nodemcuv2         # NodeMCU 1.0 (ESP8266) — LED on GPIO2 (D4)
```

Output binary: `.pio/build/<env>/firmware.bin`

## Flash via the app

1. Build the sketch on a desktop (PlatformIO compiles cannot run on-phone).
2. Copy `firmware.bin` to the phone.
3. Open Spark → **Firmware** tab → switch to **USB** mode.
4. Plug the chip in through an OTG cable. Grant USB permission when prompted.
5. Flash offset: `0x10000` (this is an app-only build, not a merged image).
6. Pick the `.bin`, hit **Flash**. Should take ~10s at 115200.

If the LED starts blinking after the chip resets, the flasher works.

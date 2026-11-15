# spark-mcu firmware

PlatformIO project. Builds for ESP32 (`esp32dev`), ESP32-S3 DevKitC-1 (`esp32-s3-devkitc-1`), and NodeMCU v2 / ESP8266 (`nodemcuv2`) from the same `src/main.cpp`.

```
pio run -e esp32dev
pio run -e esp32dev -t upload
pio run -e esp32-s3-devkitc-1
pio device monitor
```

## First boot

Boots into WiFi AP mode as `spark-mcu-xxxx`. Connect with a phone, browser auto-launches the portal, enter home WiFi creds, reboot. From then on it joins that network and registers on mDNS as `spark-mcu-xxxx.local`.

## Pin defaults

Two-motor H-bridge convention (override in `handleCommand` if your wiring differs):

| Signal | ESP32 | ESP32-S3 | ESP8266 |
| --- | --- | --- | --- |
| L_IN1 / L_IN2 / L_PWM | 16 / 17 / 18 | 4 / 5 / 6 | D1 / D2 / D5 |
| R_IN1 / R_IN2 / R_PWM | 19 / 21 / 22 | 7 / 15 / 16 | D6 / D7 / D8 |

Servos: any free PWM-capable pin. ESP32 supports 8 concurrent, ESP8266 supports 4.

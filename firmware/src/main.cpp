// spark-mcu firmware
// WebSocket server + JSON command protocol for ESP32 / ESP8266.

#include <Arduino.h>
#include <ArduinoJson.h>
#include <WebSocketsServer.h>
#include <WiFiManager.h>

#if defined(SPARK_BOARD_ESP32)
  #include <WiFi.h>
  #include <ESPmDNS.h>
  #include <ESP32Servo.h>
#else
  #include <ESP8266WiFi.h>
  #include <ESP8266mDNS.h>
  #include <Servo.h>
#endif

static WebSocketsServer ws(81);
static String g_hostname;

#if defined(SPARK_BOARD_ESP32)
static Servo g_servos[8];
static int g_servoPins[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
#else
static Servo g_servos[4];
static int g_servoPins[4] = {-1, -1, -1, -1};
#endif

static int attachServo(int pin) {
    for (size_t i = 0; i < sizeof(g_servoPins) / sizeof(int); i++) {
        if (g_servoPins[i] == pin) return i;
    }
    for (size_t i = 0; i < sizeof(g_servoPins) / sizeof(int); i++) {
        if (g_servoPins[i] == -1) {
            g_servos[i].attach(pin);
            g_servoPins[i] = pin;
            return i;
        }
    }
    return -1;
}

static void broadcastJson(const JsonDocument& doc) {
    String out;
    serializeJson(doc, out);
    ws.broadcastTXT(out);
}

static void sendJson(uint8_t num, const JsonDocument& doc) {
    String out;
    serializeJson(doc, out);
    ws.sendTXT(num, out);
}

static void handleCommand(uint8_t num, const String& payload) {
    JsonDocument doc;
    if (deserializeJson(doc, payload)) {
        JsonDocument err;
        err["evt"] = "error";
        err["msg"] = "bad json";
        sendJson(num, err);
        return;
    }
    const char* cmd = doc["cmd"] | "";

    if (strcmp(cmd, "ping") == 0) {
        JsonDocument r;
        r["evt"] = "pong";
        r["uptime_ms"] = (uint32_t)millis();
        r["host"] = g_hostname;
        sendJson(num, r);

    } else if (strcmp(cmd, "pin") == 0) {
        int pin = doc["pin"] | -1;
        const char* mode = doc["mode"] | "out";
        int value = doc["value"] | 0;
        if (pin < 0) return;
        if (strcmp(mode, "out") == 0) {
            pinMode(pin, OUTPUT);
            digitalWrite(pin, value ? HIGH : LOW);
        } else if (strcmp(mode, "in") == 0) {
            pinMode(pin, INPUT);
        } else if (strcmp(mode, "pullup") == 0) {
            pinMode(pin, INPUT_PULLUP);
        }
        JsonDocument r;
        r["evt"] = "pin";
        r["pin"] = pin;
        r["mode"] = mode;
        r["value"] = value;
        broadcastJson(r);

    } else if (strcmp(cmd, "read") == 0) {
        int pin = doc["pin"] | -1;
        if (pin < 0) return;
        int v = analogRead(pin);
        JsonDocument r;
        r["evt"] = "read";
        r["pin"] = pin;
        r["value"] = v;
        sendJson(num, r);

    } else if (strcmp(cmd, "servo") == 0) {
        int pin = doc["pin"] | -1;
        int angle = doc["angle"] | 90;
        if (pin < 0) return;
        int idx = attachServo(pin);
        if (idx >= 0) {
            g_servos[idx].write(constrain(angle, 0, 180));
        }
        JsonDocument r;
        r["evt"] = "servo";
        r["pin"] = pin;
        r["angle"] = angle;
        broadcastJson(r);

    } else if (strcmp(cmd, "motor") == 0) {
        // Two-motor H-bridge convention:
        // ESP32: L_IN1=16, L_IN2=17, L_PWM=18; R_IN1=19, R_IN2=21, R_PWM=22
        // ESP8266: L_IN1=D1, L_IN2=D2, L_PWM=D5; R_IN1=D6, R_IN2=D7, R_PWM=D8
        int left = doc["left"] | 0;
        int right = doc["right"] | 0;
#if defined(SPARK_BOARD_ESP32)
        const int L_IN1 = 16, L_IN2 = 17, L_PWM = 18;
        const int R_IN1 = 19, R_IN2 = 21, R_PWM = 22;
#else
        const int L_IN1 = 5, L_IN2 = 4, L_PWM = 14;
        const int R_IN1 = 12, R_IN2 = 13, R_PWM = 15;
#endif
        pinMode(L_IN1, OUTPUT); pinMode(L_IN2, OUTPUT); pinMode(L_PWM, OUTPUT);
        pinMode(R_IN1, OUTPUT); pinMode(R_IN2, OUTPUT); pinMode(R_PWM, OUTPUT);
        digitalWrite(L_IN1, left >= 0 ? HIGH : LOW);
        digitalWrite(L_IN2, left >= 0 ? LOW : HIGH);
        analogWrite(L_PWM, constrain(abs(left), 0, 255));
        digitalWrite(R_IN1, right >= 0 ? HIGH : LOW);
        digitalWrite(R_IN2, right >= 0 ? LOW : HIGH);
        analogWrite(R_PWM, constrain(abs(right), 0, 255));
        JsonDocument r;
        r["evt"] = "motor";
        r["left"] = left;
        r["right"] = right;
        broadcastJson(r);
    }
}

static void onWsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_CONNECTED: {
            JsonDocument r;
            r["evt"] = "hello";
            r["host"] = g_hostname;
            r["board"] =
#if defined(SPARK_BOARD_ESP32)
                "esp32";
#else
                "esp8266";
#endif
            sendJson(num, r);
            break;
        }
        case WStype_TEXT: {
            String payloadStr((const char*)payload, length);
            handleCommand(num, payloadStr);
            break;
        }
        default: break;
    }
}

void setup() {
    Serial.begin(115200);
    delay(100);

    uint32_t chipId =
#if defined(SPARK_BOARD_ESP32)
        (uint32_t)ESP.getEfuseMac();
#else
        ESP.getChipId();
#endif
    char buf[32];
    snprintf(buf, sizeof(buf), "spark-mcu-%04x", (uint16_t)(chipId & 0xffff));
    g_hostname = buf;

    WiFiManager wm;
    wm.setConfigPortalTimeout(180);
    if (!wm.autoConnect(g_hostname.c_str())) {
        Serial.println("wifi failed, rebooting");
        delay(2000);
        ESP.restart();
    }

#if defined(SPARK_BOARD_ESP32)
    WiFi.setHostname(g_hostname.c_str());
#else
    WiFi.hostname(g_hostname);
#endif

    if (MDNS.begin(g_hostname.c_str())) {
        MDNS.addService("spark", "tcp", 81);
    }

    ws.begin();
    ws.onEvent(onWsEvent);

    Serial.printf("spark-mcu up at %s (%s), ws port 81\n",
                  g_hostname.c_str(),
                  WiFi.localIP().toString().c_str());
}

void loop() {
    ws.loop();
#if !defined(SPARK_BOARD_ESP32)
    MDNS.update();
#endif

    static uint32_t lastTick = 0;
    uint32_t now = millis();
    if (now - lastTick > 5000) {
        lastTick = now;
        JsonDocument r;
        r["evt"] = "tick";
        r["uptime_ms"] = now;
        r["heap"] = (uint32_t)ESP.getFreeHeap();
        broadcastJson(r);
    }
}

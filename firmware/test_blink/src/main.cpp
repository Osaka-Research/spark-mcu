#include <Arduino.h>

#ifndef LED_PIN
#define LED_PIN 2
#endif

void setup() {
    Serial.begin(115200);
    pinMode(LED_PIN, OUTPUT);
    Serial.println();
    Serial.println("spark-mcu · test_blink");
    Serial.print("LED on GPIO ");
    Serial.println(LED_PIN);
}

void loop() {
    digitalWrite(LED_PIN, HIGH);
    Serial.println("tick");
    delay(500);
    digitalWrite(LED_PIN, LOW);
    delay(500);
}

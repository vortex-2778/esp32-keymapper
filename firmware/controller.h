#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Please enable Bluetooth Classic.
#endif

BluetoothSerial SerialBT;

// 15 GPIO pins
const byte buttonPins[15] = {
  4, 13, 14, 16, 17,
  18, 19, 21, 22, 23,
  25, 26, 27, 32, 33
};

const int onboardLedPin = 2;
const int BUTTON_COUNT = 15;

bool lastButtonStates[BUTTON_COUNT];
bool stableButtonStates[BUTTON_COUNT];
unsigned long lastDebounceTime[BUTTON_COUNT];

const unsigned long debounceDelay = 35;
const unsigned long ledDuration = 100;

unsigned long ledOnTime = 0;
bool ledActive = false;
bool wasConnected = false;

void setup() {
  Serial.begin(115200);

  SerialBT.begin("ESP32 Sim-Race Controller");
  Serial.println("Bluetooth started. Ready to pair!");

  for (int i = 0; i < BUTTON_COUNT; i++) {
    pinMode(buttonPins[i], INPUT_PULLUP);

    bool state = digitalRead(buttonPins[i]);
    lastButtonStates[i] = state;
    stableButtonStates[i] = state;
    lastDebounceTime[i] = millis();
  }

  pinMode(onboardLedPin, OUTPUT);
  digitalWrite(onboardLedPin, LOW);
}

void sendButtonEvent(int index, bool pressed) {
  if (!SerialBT.hasClient()) return;

  SerialBT.print(pressed ? "BTN_PRESS_" : "BTN_RELEASE_");
  SerialBT.println(index + 1);
}

void blinkLed() {
  digitalWrite(onboardLedPin, HIGH);
  ledOnTime = millis();
  ledActive = true;
}

void loop() {
  bool isConnectedNow = SerialBT.hasClient();

  if (wasConnected && !isConnectedNow) {
    Serial.println("Bluetooth disconnected");
    wasConnected = false;
  }

  if (!wasConnected && isConnectedNow) {
    Serial.println("Bluetooth connected");
    wasConnected = true;
  }

  for (int i = 0; i < BUTTON_COUNT; i++) {
    bool reading = digitalRead(buttonPins[i]);

    if (reading != lastButtonStates[i]) {
      lastDebounceTime[i] = millis();
    }

    if (millis() - lastDebounceTime[i] >= debounceDelay) {
      if (reading != stableButtonStates[i]) {
        stableButtonStates[i] = reading;

        bool pressed = (stableButtonStates[i] == LOW);

        sendButtonEvent(i, pressed);

        Serial.printf(
          "Switch %d %s\n",

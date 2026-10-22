# Firmware

`controller.h` is the ESP32 sketch used by the app (Arduino IDE, **Bluetooth
Classic** enabled: *Tools → Partition Scheme* and *ESP32 Dev Module*).

It advertises the Bluetooth name **`ESP32 Sim-Race Controller`** and sends one
ASCII line per button edge over SPP:

```
BTN_PRESS_1\n
BTN_RELEASE_1\n
```

## Pins

| Button | GPIO | Button | GPIO | Button | GPIO |
|-------:|-----:|-------:|-----:|-------:|-----:|
| 1 | 4  | 6  | 18 | 11 | 25 |
| 2 | 13 | 7  | 19 | 12 | 26 |
| 3 | 14 | 8  | 21 | 13 | 27 |
| 4 | 16 | 9  | 22 | 14 | 32 |
| 5 | 17 | 10 | 23 | 15 | 33 |

All pins use `INPUT_PULLUP`, so wire each button between the GPIO and GND.
The on-board LED (GPIO 2) blinks on every button event.

## Note

The uploaded `controller.h` ends in the middle of the `Serial.printf(...)` call
inside `loop()` (line 91), so the copy in this folder is truncated. The
Bluetooth part used by the Android app is complete. The missing tail is
presumably:

```c
          "Switch %d %s\n",
          i + 1,
          pressed ? "pressed" : "released"
        );
        blinkLed();
      }
    }
    lastButtonStates[i] = reading;
  }

  if (ledActive && millis() - ledOnTime >= ledDuration) {
    digitalWrite(onboardLedPin, LOW);
    ledActive = false;
  }
}
```

Add the missing lines (and the blink on press) before flashing.

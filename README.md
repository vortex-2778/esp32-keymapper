# ESP32 Key Mapper

Android app that turns the 15 buttons of an **ESP32 Bluetooth Classic** controller
into on-screen touches for Android games.

The ESP32 sketch (`controller.h` in the parent folder) sends one ASCII line per
button edge over Bluetooth SPP:

```
BTN_PRESS_1\n
BTN_RELEASE_1\n
...
BTN_PRESS_15\n
BTN_RELEASE_15\n
```

The app reads those lines, draws a floating overlay with all 15 buttons, and uses
an **AccessibilityService** to inject the configured touch / gesture into the game.

---

## Features

- **Bluetooth Classic (SPP)** connection to `ESP32 Sim-Race Controller`, with
  automatic reconnection.
- **Foreground service**: the link and the overlay keep running while the app is
  in the background or the screen is showing another app.
- **Floating overlay** with all 15 buttons. Every button can be enabled or
  disabled individually and is highlighted while it is held.
- **One global opacity slider** for every button at the same time.
- **Three actions per button**
  - `Hold` – finger stays down while the physical button is pressed (steering,
    throttle, brake…),
  - `Tap` – a quick tap on press,
  - `Drawn gesture` – a custom path recorded with your finger and replayed on
    press (swipes, aim drags, camera turns…).
- **Multi-touch**: every press/release is handled independently, so several
  buttons can be down at the same time (up to 10 fingers / Android's limit).
- **Gesture recorder**: draw directly on the layout preview, or full-screen
  (also reachable from the notification) to trace the real game UI.
- Positions and gestures are stored **normalized (0…1)**, so they survive
  rotation and different screen sizes.

---

## Where are the files

```
Esp32KeyMapper/
├── app/src/main/AndroidManifest.xml
├── app/src/main/res/xml/accessibility_service_config.xml
├── app/src/main/res/values/{strings,themes}.xml
├── app/src/main/java/com/esp32/keymapper/
│   ├── core/Config.kt              data model + JSON persistence
│   ├── core/ControllerHub.kt       shared state / event bus
│   ├── core/ScreenUtils.kt
│   ├── bt/BtSppClient.kt           Bluetooth SPP reader + auto-reconnect
│   ├── service/ControllerService.kt foreground service (BT + overlay)
│   ├── service/NotificationHelper.kt
│   ├── access/KeyMapperAccessibilityService.kt
│   ├── access/GestureEngine.kt     multi-touch gesture injection
│   ├── overlay/OverlayController.kt
│   ├── overlay/ButtonOverlayView.kt
│   ├── overlay/GestureCaptureOverlay.kt
│   ├── ui/MainActivity.kt          connection + settings
│   ├── ui/EditorActivity.kt        layout / gesture editor
│   └── ui/LayoutEditorView.kt
└── build.gradle.kts, settings.gradle.kts, gradlew …
```

---

## Build

This folder is a complete Gradle project (AGP 8.5.2, Kotlin 1.9.24, compileSdk 34,
minSdk 26). It was **not compiled in the Termux session** that created it because
that environment has no JDK / Android SDK.

### Android Studio (recommended)

1. Copy the folder to your PC.
2. *File → Open* → select `Esp32KeyMapper`.
3. Let Gradle sync (it downloads AGP, Kotlin and AndroidX).
4. *Build → Build APK(s)*, or run on a device.

### Command line

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## First-run setup

1. **Pair the controller** in Android *Settings → Bluetooth*. The sketch
   advertises `ESP32 Sim-Race Controller`.
2. Open the app and grant:
   - **Nearby devices / Bluetooth** permission,
   - **Display over other apps** (*Overlay access* button),
   - **Notifications** (Android 13+) so the foreground service can show its
     status,
   - **Accessibility**: tap *Accessibility*, then enable *ESP32 Key Mapper*.
3. Tap **Start service** → the notification appears and the overlay is shown.
4. Pick the controller in the *Controller* card and tap **Connect**.
5. Open the game. Press a physical button – the matching circle lights up and the
   touch is injected.

### Mapping buttons

- Tap **Edit layout & gestures** (or *Edit* next to a button).
- Drag circles on the preview, resize them with **Size**, rename with **Label**.
- **Action** chooses Hold / Tap / Drawn gesture.
- **Draw here** – draw the gesture on the preview with one finger, lift to save.
- **Full screen** – record the gesture across the whole real screen (useful to
  trace a game control). The toolbar at the top lets you pick the target button.
- **All buttons opacity** changes every button at once.
- Changes are saved immediately and appear on the live overlay.

From the notification you can also tap **Record gesture** at any time while a
game is in front; the full-screen recorder opens on top of it.

---

## How multi-touch injection works

Android's accessibility API can dispatch only **one GestureDescription at a
time**, and every new dispatch cancels the running one. To keep buttons
independent the app packs **all currently held buttons into one gesture**, one
stroke (finger) per button, and re-dispatches the combined gesture whenever the
set of held buttons changes:

- press B1 → one stroke at B1,
- press B3 while B1 is held → one gesture with two strokes (B1 + B3),
- release B1 → one gesture with only B3.

Consequences you should know about:

- When the set of held buttons changes, Android lifts and re-touches the fingers
  that stay held (a very short `UP`/`DOWN`). This is a platform limitation of
  non-root accessibility injection. For racing games it is usually invisible;
  for games that count exact frames it can drop a frame.
- Releasing the last held button needs a cancel; a 1 ms stroke is dispatched at
  that button's position. Some games may register it as an extra quick tap.
- Android limits a gesture to 60 s; long holds are automatically re-dispatched
  every ~55 s.
- At most 10 simultaneous pointers (Android's `getMaxStrokeCount()`).

If a game needs perfect multi-touch, a root/Shizuku based injector would be
required instead of the accessibility service.

---

## Protocol details / adapting other firmwares

`ControllerHub.onSerialLine` matches this regex:

```
BTN_(PRESS|RELEASE)[_: -]*(\d{1,2})
```

So `BTN_PRESS_7`, `BTN_RELEASE_7`, `BTN_PRESS:7` all work, and indices 1…15 map
to the buttons in the app. If your sketch uses another format, edit
`lineRegex` in `core/ControllerHub.kt`.

Bluetooth SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` (the standard one used
by `BluetoothSerial`).

---

## Battery / background notes

The foreground service keeps the RFCOMM socket open and the overlay alive, and
reconnects every 4 s if the link drops. Some vendors (Xiaomi, Samsung, OnePlus…)
kill background services aggressively – exclude the app from battery
optimisation if the connection drops when the screen is off.

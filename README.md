# BT Gamepad Pro

Native Android touchscreen gamepad that attempts to expose the controller phone as a standard Bluetooth HID Game Pad.

## Features in this starter

- Dual 360-degree virtual analog sticks with L3/R3 click
- D-pad, A/B/X/Y, L1/L2/R1/R2, Start and Select
- Adjustable analog deadzone, sensitivity and stick size
- Landscape controller layout
- Bluetooth HID Device registration and connection status
- GitHub Actions debug APK build

## Important compatibility note

This app uses Android Bluetooth HID Device profile (introduced in Android 9 / API 28). Android manufacturers are not required to include the optional HID Device system profile, so some phones cannot act as a Bluetooth gamepad even when Bluetooth itself works. The app checks for the profile and displays an unsupported message if it is missing.

The receiving phone must support Bluetooth HID host/gamepad input. No companion receiver app is required when the receiving phone recognizes standard Bluetooth gamepads. Pairing and game compatibility depend on the receiving phone and game.

## Pairing

1. Install and open BT Gamepad Pro on the controller phone.
2. Tap HUBUNGKAN and allow the Bluetooth permission prompts.
3. Accept the Android discoverability prompt.
4. On the receiving phone, open Bluetooth settings and pair with BT Gamepad Pro.
5. Open a game that supports a physical HID gamepad.

If the controller phone reports that HID Device is unavailable, that is a device firmware limitation; this app cannot add a missing system Bluetooth profile without manufacturer/system-level support.

## Build APK

The workflow at .github/workflows/build-apk.yml builds a debug APK on every push to main and on manual dispatch.

Open the repository Actions tab, select Build BT Gamepad Pro APK, then download the BT-Gamepad-Pro-debug artifact from a successful run.

## Development

- Kotlin
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- minSdk 28 (Android 9)

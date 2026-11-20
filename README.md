# BT Gamepad Pro

Native Android touchscreen controller that exposes the controller phone as a Bluetooth Classic HID gamepad.

## Features

- Dual 360-degree virtual analog sticks
- L3/R3, D-pad, A/B/X/Y, L1/L2/R1/R2, Start and Select
- Adjustable deadzone, sensitivity and analog stick size
- Landscape layout
- Bluetooth HID Device registration and explicit host connection
- GitHub Actions debug APK build

## Compatibility

The controller phone must include Android's optional Bluetooth HID Device profile (API 28 / Android 9 or newer). Android manufacturers are not required to include this profile, so Bluetooth may work normally while HID Device is unavailable. The app reports this condition rather than pretending to connect.

The receiving phone must support Bluetooth HID Host/gamepad input. The target game must accept a standard physical gamepad.

## Correct pairing sequence

1. Open BT Gamepad Pro on the **controller phone** and keep the app open on screen.
2. Tap **HUBUNGKAN**. Android asks permission to make the controller phone discoverable; accept it.
3. After Android returns to BT Gamepad Pro, wait for the status to say **HID AKTIF**.
4. On the **receiving phone**, open Bluetooth settings, scan for nearby devices, and pair with the **Bluetooth name of the controller phone** (for example, the phone's own device name). The device may not appear as “BT Gamepad Pro” because Android controls the phone's Bluetooth adapter name.
5. Return to BT Gamepad Pro on the controller phone and tap **HUBUNGKAN** again.
6. Choose the receiving phone from the paired-device list. Wait for the status to say **TERHUBUNG**.
7. Keep BT Gamepad Pro open in the foreground while playing. Android can automatically unregister a HID Device app when it is no longer in the foreground.

If the controller phone reports that HID Device is unavailable, this is a firmware limitation. An ordinary app cannot add a missing system Bluetooth HID profile.

## Build APK

GitHub Actions builds a debug APK on every push to `main` and on manual dispatch.

Open **Actions** > **Build BT Gamepad Pro APK** > a successful run > **Artifacts** > `BT-Gamepad-Pro-debug`.

## Development

- Kotlin
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- minSdk 28 (Android 9)

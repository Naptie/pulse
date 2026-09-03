# Pulse — vitals monitor (Kotlin Multiplatform)

A mobile vitals monitor built with **Kotlin Multiplatform**: one shared Kotlin engine
(BLE scanning, GATT streaming, per-device 5-minute history) with fully native
UIs — **SwiftUI + Liquid Glass** on iOS and **Jetpack Compose** on Android.

## Features

- Scan for nearby BLE devices; vitals-capable devices are pinned on top and the
  whole list re-sorts live by signal strength (RSSI)
- Connect to a heart-rate service (GATT 0x180D / 0x2A37) and stream real-time BPM
  (`2A37` UINT8/UINT16, notifications)
- Live monitor: animated pulse, zone chip (Rest / Normal / Elevated / Exercise / Peak)
- 5-minute history chart per device, retained in memory across sessions — stop
  measuring, switch devices, come back; each device keeps its own window. Inactive time
  windows render as blank areas, never connecting nodes
- Interactive chart cursor (drag to read BPM, auto-dismisses after 3 s)
- Remembers the last connected device; one-tap Start measuring reconnect;
  dialog with retry when a device can no longer be reached
- Background measurement on iOS via `UIBackgroundModes = bluetooth-central`
- Simulator-friendly: on the iOS simulator the app runs a built-in mock vitals
  source so the whole UI can be exercised without hardware

## Project layout

```
shared/     KMP module: BLE platform code (Android BluetoothLeScanner+GATT,
            iOS CoreBluetooth), HeartHistory, MockPlatform, HR parser
androidApp/ Jetpack Compose app (Material 3, dark theme)
iosApp/     SwiftUI app (iOS 26 Liquid Glass: glass cards, glass tab bar,
            Swift Charts) + Pulse.xcodeproj
```

## Building

Prerequisites: JDK 17+, Android SDK (for Android), Xcode 26+ and the iOS 26 SDK (for iOS).

```bash
./gradlew :androidApp:assembleDebug      # Android debug APK
./gradlew :androidApp:assembleRelease    # Android release APK (unsigned)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64   # Kotlin iOS framework
```

### iOS (Xcode)

Open `iosApp/Pulse.xcodeproj`, set your own `DEVELOPMENT_TEAM` in
`Signing & Capabilities`, and run on a device or simulator. The build phase invokes
`./gradlew :shared:embedAndSignAppleFrameworkForXcode` automatically.

Command line:

```bash
xcodebuild -project Pulse.xcodeproj -target Pulse -configuration Debug \
  -sdk iphonesimulator -arch arm64 build
```

Note: the project ships with `DEVELOPMENT_TEAM = 7VMT2FDAR8` in the build settings —
replace it with your own team.

### Android (signing)

APKs are debug-signed by default. For release builds, sign with your own keystore:

```bash
zipalign -f -p 4 app-unsigned.apk aligned.apk
apksigner sign --ks your.keystore --ks-pass pass:*** --out Pulse.apk aligned.apk
```

## CI (GitHub Actions)

`.github/workflows/build.yml` builds on workflow_dispatch (or push to `main`):

- **Android job** (ubuntu): `assembleRelease` → zipalign + apksigner sign → artifact `pulse-android-<version-name>`
- **iOS job** (macos): unsigned archive → `.ipa` → artifact `pulse-ios-<version-name>`
- Version + build timestamp come from `gradle.properties` (`pulse.version`) and the run time;
  files are named `Pulse_<version>_<MMDD-HHMMSS>.apk` / `.ipa` (no framework label — native builds)

Required repo secrets:

- `RELEASE_KEYSTORE` — base64 of your `.keystore`
- `RELEASE_KEYSTORE_PASSWORD` — keystore key password

## Permissions

- Android: `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+); classic Bluetooth +
  `ACCESS_FINE_LOCATION` (API ≤ 30, required for BLE scans)
- iOS: `NSBluetoothAlwaysUsageDescription`, background mode `bluetooth-central`

## SPP adapter (branch `spp`)

The `spp` branch adds a JDY-31 classic-Bluetooth SPP adapter for team devices
that are not BLE:

- Module: JDY-31 (SPP transparent serial, UART 3.3V, pair via phone settings)
- Wire protocol: CRLF-terminated ASCII lines, ~2 Hz:
  `HR=98 SPO2=91%` (`--` when a sensor value is invalid)
- Android: classic discovery (BLUETOOTH_SCAN) + RFCOMM socket
  (`00001101-0000-1000-8000-00805f9b34fb`), merged with the BLE scan via
  `AndroidHybridPlatform`; the engine and UIs are unchanged
- iOS: CoreBluetooth has no classic-SPP access — that path needs MFi-certified
  accessory hardware, so this branch keeps the BLE-only iOS platform
- Parser unit tests: `./gradlew :shared:testDebugUnitTest`

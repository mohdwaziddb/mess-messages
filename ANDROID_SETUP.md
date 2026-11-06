# Android setup

## Toolchain

| Tool | Version | Notes |
|---|---|---|
| Node.js | ≥ 22.13 | |
| JDK | **21** | Required by libsignal-client 0.106 (the JVM unit tests run on 21). The app module still compiles to Java 17 bytecode, as the React Native Gradle plugin requires. |
| Android SDK | platform `android-37.0`, build-tools `37.0.0`, platform-tools | |
| NDK | 27.1.12297006 | Needed by React Native's new architecture |
| CMake | 3.22.1 | |

These are already installed on the dev PC:

- SDK: `D:\android-sdk` (point `android/local.properties` at it with `sdk.dir=D\:/android-sdk`)
- JDK: `C:\Users\EDUNEXT\.jdks\corretto-21.0.6`

```bash
export JAVA_HOME=/c/Users/EDUNEXT/.jdks/corretto-21.0.6
cd android && ./gradlew :core:test assembleRelease
```

On a fresh machine, the SDK command-line tools install everything:

```bash
sdkmanager --sdk_root=<sdk> "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
```

libsignal comes from Signal's Maven repository, `https://build-artifacts.signal.org/libraries/maven/`. It is configured in `android/build.gradle`. **Internet is needed at build time only.**

## Runtime permissions

| Android version | Permissions requested | Why |
|---|---|---|
| 12+ (API 31+) | `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Find, be found by and connect to nearby phones |
| 11 and lower | `ACCESS_FINE_LOCATION` (plus legacy `BLUETOOTH` and `BLUETOOTH_ADMIN`, granted at install) | The OS requires it for BLE scans. We never read location. |
| 13+ | `POST_NOTIFICATIONS` (optional) | New-message alerts. Message text is never shown. |
| All | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (install-time) | Background discovery and relay, with a visible notification |
| All | `CAMERA` (requested only when the scanner opens) | Scanning QR codes, decoded fully on the device |

`NEARBY_WIFI_DEVICES` is **not** requested. Wi-Fi Direct and Wi-Fi Aware are not used for data in this beta, and we only ask for what is actually used.

Permissions are requested in context, on the Security Setup screen or when a feature is first used, and each one comes with an explanation. If a permission was permanently denied, the app links to the system settings. It never tries to get around OS permission controls.

## Battery and OEM settings

Some vendors kill background services. In those cases the user should allow Offline Mesh to run unrestricted:

- **Xiaomi / Redmi / POCO** (MIUI / HyperOS): App info → Autostart ON; Battery saver → No restrictions.
- **Oppo / Realme / OnePlus** (ColorOS): App info → Battery usage → Allow background activity.
- **Vivo:** Settings → Battery → Background power consumption → allow.
- **Samsung:** Settings → Battery → Background usage limits → remove from "Sleeping apps".
- **Huawei:** App launch → Manage manually → allow all.

The Troubleshooting screen inside the app checks each of these conditions and opens the right settings page.

# Build and release

## Builds

| Command | Output | Notes |
|---|---|---|
| `./gradlew :core:test` | JUnit report in `android/core/build/reports/tests` | Runs on the PC with real libsignal and real SQL |
| `npx jest` | | UI component and store tests |
| `npx tsc --noEmit && npx eslint App.tsx src` | | Type check and lint |
| `npx react-native run-android` | debug APK | Needs Metro (keeps the INTERNET permission for the dev server) |
| `./gradlew assembleRelease` | `android/app/build/outputs/apk/release/app-release.apk` | JS is bundled into the APK. **No INTERNET permission.** ABIs: arm64-v8a and armeabi-v7a. |

> The release build is currently signed with the **debug keystore** so beta APKs can be side-loaded for testing. **Before any public distribution**, create a private release keystore and point `signingConfigs.release` at it, using values from `~/.gradle/gradle.properties` (never commit them). Losing that key means users can't update the app.

## Install the beta on two phones

1. On each phone, enable installing apps from unknown sources for your file manager.
2. Copy `app-release.apk` to each phone (USB or Bluetooth file share) and install it. Or, with USB debugging on: `adb install -r app-release.apk`.

## Release checklist

- [ ] `./gradlew :core:test` passes
- [ ] `npx jest`, `tsc` and `eslint` pass
- [ ] The release manifest has no `android.permission.INTERNET`: run `aapt dump permissions app-release.apk`
- [ ] Database encrypted: on a debug build, `adb exec-out run-as com.offlinemesh cat no_backup/mesh.db > m.db`; then `sqlite3 m.db .tables` must fail with "file is not a database"
- [ ] Every row of the manual device test plan (TESTING.md) has been run on at least 2 manufacturers
- [ ] Private release keystore configured
- [ ] Version code bumped
- [ ] AGPL source offer included (libsignal)
- [ ] Hardening: turn on R8 (`enableProguardInReleaseBuilds`) only after adding keep rules for libsignal JNI and SQLCipher, then retest on device

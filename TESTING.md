# Testing

## 1. Automated tests

### JVM protocol tests — `android/core` (`./gradlew :core:test`)

These tests run the **real libsignal** (the JVM build) and **real SQL** (sqlite-jdbc) through the same engine code that runs on the phone. Several phones are simulated by an in-memory radio (`Sim.kt`) that chunks every frame at a BLE-like MTU of 185 bytes.

| Area | Tests |
|---|---|
| Crypto | session round-trip; replayed ciphertext rejected; envelope tamper, wrong recipient and wrong AAD rejected; safety number is the same on both sides; link-auth signature bound to the nonces; QR encode/decode |
| Two phones | discover → handshake → names exchanged inside E2E → chat both ways → delivered → read receipts |
| Confidentiality | plaintext never appears in any bytes on the wire |
| Offline delivery | queued while disconnected → stays `pending` (never falsely delivered) → delivered on reconnect |
| Expiry | `failed` after the 7-day TTL; retry re-encrypts |
| Mesh | 3-phone relay A→B→C delivered, with the receipt routed back; **relay B cannot open the packets**; relay disabled → no forwarding; store-and-forward through a phone that moves between A and C |
| Attacks | replayed packets ignored; tampered packet rejected and logged; malformed and garbage input dropped; replayed AUTH frame on a fresh link rejected; forged group change rejected; a non-admin can't remove members |
| Groups | create → invite → accept → messages in both directions; removed member cannot read later messages and cannot send |
| Files | 100 KB transfer split into chunks, with integrity checked by SHA-256 |
| QR | mutual verification; a reused token is rejected; an expired code is refused |
| Identity | same name + different key → warning; blocked contact cannot connect |
| Persistence | engine restart over the same DB keeps sessions and history |
| Framing | out-of-order and duplicate chunks; CRC corruption; reassembly timeout; oversize and invalid fields; duplicate physical links resolve to the same link on both sides |

### React Native tests (`npx jest`)

- Component rendering and interaction: Button, Avatar, Pill, Banner.
- Formatting helpers.
- Store: loads native state; retries while encrypted storage is still opening; never invents stats.

## 2. What automated tests can't prove

The JVM simulation does **not** exercise the real radio stack. That means it doesn't cover:

- BLE advertising and scanning;
- GATT MTU negotiation;
- OEM background killing;
- the Android Keystore or SQLCipher on a phone;
- foreground-service behaviour.

Those need physical devices. Emulators have no BLE.

## 3. Manual device test plan

Record the phone models and Android versions for each run.

| # | Test | Steps | Pass criteria |
|---|---|---|---|
| A1 | Install | Install `app-release.apk` on phones A and B | Opens to the Welcome screen |
| A2 | No network | Airplane mode ON, then Bluetooth ON (Wi-Fi and data off, no SIM needed) | — |
| A3 | Onboarding | Create a profile on each phone and grant Nearby permission | Fingerprint shown; "Hardware-protected" on most phones |
| A4 | Discovery | Open Nearby on both phones, 1–5 m apart | Each phone lists the other within about 30 s, first as a device, then as a named person after the secure connection |
| B1 | QR pairing | A opens My QR, B scans it | B shows "Verified"; A also marks B as verified |
| B2 | Safety number | Open the profile on both phones | The same 60 digits on both |
| C1 | Chat A→B, B→A | Send texts both ways | Arrive; ticks go clock → ✓ → ✓✓ (delivered) → teal ✓✓ (read) |
| C2 | Out of range | Walk B out of range; A sends | A stays at clock or ✓; after B returns, it is delivered |
| C3 | Restart | Force-stop both apps and reopen | History intact; chatting continues without re-pairing |
| C4 | DB encryption | Debug build: pull `no_backup/mesh.db` | `sqlite3` reports "file is not a database" |
| C5 | No server | Release APK: `aapt dump permissions` | No `INTERNET` permission |
| D1 | File | Send a photo of 1–3 MB | Arrives with progress; displays; can be saved |
| D2 | Background | App in background, screen off for 5 minutes, then send from the other phone | Notification arrives (may fail on aggressive OEMs — record which) |
| E1 | 3-phone relay | A and C out of range of each other, B in the middle; A and C met once | A→C is delivered via B; B has no message rows |
| F1 | Group (3 phones) | Create group, accept, message, then remove one member | Removed member can no longer read |

## 4. Verification status

| Item | Status |
|---|---|
| Protocol, crypto, relay, groups and attacks (JVM, real libsignal) | ✅ Automated, passing |
| UI components and store (Jest) | ✅ Automated, passing |
| Type check and lint | ✅ Passing |
| Release APK builds | ✅ `dist/OfflineMesh-0.1.0-beta.apk`. `aapt dump permissions` shows **no INTERNET**; JS bundle and arm64/armv7 natives (libsignal, SQLCipher, Hermes) are present |
| A1–D2 on two physical phones | ⏳ **Not yet run.** Needs the user's two phones |
| E1, F1 (3 phones) | ⏳ **Not verified on hardware.** Only 2 phones available; covered by JVM simulation only |
| Battery / memory profiling | ⏳ Not yet measured |
| Multiple manufacturers | ⏳ Pending |

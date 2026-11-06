# Offline Mesh

Offline Mesh is an Android messenger for people who are physically near each other. It works without the internet, mobile data, a SIM card, a server, a router or any extra hardware.

**Install → Open → Find nearby → Connect → Chat.**

- **No account.** Your identity is a key pair generated on the phone. There's no phone number, email, OTP or login.
- **Discovery over Bluetooth LE.** Phones advertise a random id that changes every 15 minutes. The advertisement contains no name and no key.
- **End-to-end encryption with the Signal Protocol** (via [libsignal](https://github.com/signalapp/libsignal)):
  - PQXDH key agreement (X25519 + Kyber-1024)
  - Double Ratchet for private chats
  - Sender Keys for groups
- **Optional QR pairing** with mutual verification, plus 60-digit safety numbers.
- **Private chats, groups and files.** Group membership changes are signed, and keys rotate on every change.
- **Multi-hop mesh relay with store-and-forward.** Relays only ever handle sealed packets.
- **Encrypted storage.** Messages are kept in a SQLCipher database and attachments are AES-GCM encrypted. Keys are wrapped by the Android Keystore and never backed up.
- **No network at all in release builds.** The `INTERNET` permission is removed from release builds.

> **Status: beta.** Not every part has been checked on real phones yet. Read [TESTING.md](TESTING.md) to see exactly what has been verified and how. Bluetooth range is roughly 10–50 m per hop and depends on the environment. A message is delivered only if some chain of phones running the app connects the sender and the recipient before the message expires (7 days).

## Quick start (developers)

```bash
npm install
cd android && ./gradlew :core:test        # protocol + crypto tests on the JVM (no phone needed)
cd .. && npx react-native run-android    # debug build on a connected phone
```

Toolchain: Node ≥ 22.13, JDK 21, Android SDK 37, NDK 27.1. Full steps are in [ANDROID_SETUP.md](ANDROID_SETUP.md).

## Documentation

| Doc | What's inside |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layers, modules, threading, data flow |
| [PROTOCOL_SPEC.md](PROTOCOL_SPEC.md) | Wire formats: chunks, link frames, packets, envelopes, content, group state, QR |
| [SECURITY.md](SECURITY.md) | Cryptographic design, key storage, deviations from the original spec |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Attackers, assets, mitigations, residual risks |
| [NETWORKING.md](NETWORKING.md) | BLE transport, mesh routing, limits, unsupported devices |
| [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) | Tables, migrations, what is stored where |
| [TESTING.md](TESTING.md) | Automated tests, manual device test plan, verification status |
| [ANDROID_SETUP.md](ANDROID_SETUP.md) | Toolchain and permissions per Android version |
| [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) | Debug and release builds, signing, release checklist |

## License

The app links libsignal, which is licensed under **AGPL-3.0**. If you distribute this app, its source code must also be made available under AGPL-3.0.

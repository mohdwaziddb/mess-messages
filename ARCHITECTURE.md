# Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ React Native (TypeScript)        src/                        │
│   screens/  components/  store/ (Zustand)  native/mesh.ts    │
│   UI only. Never sees keys, ciphertext or crypto state.      │
└───────────────▲──────────────────────────┬───────────────────┘
                │ events "mesh:change"     │ promises (commands / views)
┌───────────────┴──────────────────────────▼───────────────────┐
│ Android app (Kotlin)            android/app/.../offlinemesh  │
│   bridge/MeshModule      RN bridge, QR scanner (CameraX+ZXing)│
│   MeshCore               single engine thread, DB open        │
│   service/MeshService    foreground service, battery, notifs  │
│   ble/BleTransport       GATT server + client, advertise/scan │
│   storage/               KeyWrap (Keystore), CipherSql,       │
│                          EncryptedBlobStore                   │
└───────────────▲──────────────────────────┬───────────────────┘
                │ Link.send(chunk)         │ linkUp / linkChunk / linkDown
┌───────────────┴──────────────────────────▼───────────────────┐
│ core (pure Kotlin/JVM, unit-tested)   android/core           │
│   proto/   Wire, Chunker/Reassembler, LinkFrame, Packet,     │
│            Content                                           │
│   crypto/  Crypto (identity, bundles, link auth, envelope,   │
│            routing tag, safety number, QR), SignalStore      │
│   engine/  Engine (handshake, sessions, outbox, receipts,    │
│            groups, QR verification), GroupState, Queries     │
│   mesh/    RelayPolicy (quotas, rate limits, queue bounds)   │
│   db/      Sql abstraction + versioned Schema migrations     │
└──────────────────────────────────────────────────────────────┘
```

## Design rules

- **Networking, crypto and UI are kept apart.**
  - `core` knows nothing about Android or Bluetooth.
  - Transports only move opaque chunks.
  - The UI only calls named commands.
- **One engine thread.** The `Engine` is single-threaded on purpose. All callers post work to `MeshCore.exec`, which removes a whole class of races in ratchet state.
- **The same code runs in tests and on the device.** Repository SQL runs against SQLCipher on the phone and against sqlite-jdbc in JVM tests, through the small `Sql` interface. Crypto runs through the real libsignal (the JVM build of libsignal-client in tests, libsignal-android on device).
- **Nothing is faked.**
  - Status values come only from real events:
    - *pending*: not yet transmitted.
    - *sent*: transmitted, but no receipt yet.
    - *delivered* / *read*: an encrypted receipt came back from every recipient.
    - *failed*: the message expired.
  - Dashboard numbers come from database counts and live link state.

## Data flow: sending a message

1. The UI calls `Mesh.sendText(convId, text)`.
2. `Engine.sendText` saves the message as `pending` and encodes it as `Content.Text`.
3. libsignal `SessionCipher.encrypt` (Double Ratchet) produces the ciphertext.
4. `Crypto.sealEnvelope` HPKE-seals the sender identity and ciphertext to the recipient's identity key. The packet header is used as AAD.
5. A `Packet` is built with a random packetId, a routingTag (HMAC of the recipient key and the day), the TTL and the expiry. It goes into the `outbox`.
6. `transmit` sends it: directly over the link if the peer is connected, otherwise flooded to relay-capable neighbours.
7. `Chunker.split` cuts it to the BLE MTU, and the chunks are written over GATT.
8. The recipient reassembles the chunks, checks expiry and the seen-cache, and recognises the routing tag as its own.
9. It opens the envelope, decrypts with the ratchet and stores the message.
10. It sends an encrypted `Receipt(DELIVERED)`. On the sender's side, the receipt deletes the outbox row and marks the message *delivered*.

## Data flow: relaying

When a node receives a packet whose routing tag isn't its own, and relaying is enabled and the TTL is above 0, the packet goes through `RelayPolicy.admit`. That function checks battery, the per-neighbour rate limit and queue bounds.

If admitted, the packet is stored in `relay_queue` and forwarded to every other neighbour with ttl-1 and hops+1. Whenever a new neighbour connects later, still-valid queued packets are handed to it as well (store and forward).

The relay never decrypts anything: it doesn't hold the recipient's private key.

## Lifecycle

- `MainApplication.onCreate` calls `MeshCore.init`. On the engine thread this:
  - unwraps the DB and file keys through the Android Keystore;
  - opens SQLCipher;
  - runs migrations;
  - creates the `Engine`.

  A 10-second `tick` drives retries, expiry and relay-queue maintenance.
- `MeshService` (foreground, type `connectedDevice`) owns `BleTransport` and runs only after Bluetooth permissions are granted. It:
  - watches the battery (relay pauses below 20%, low-power radio below 30%);
  - posts message notifications that show only the sender or group name.
- The UI starts the service during onboarding and resumes it automatically on later launches.

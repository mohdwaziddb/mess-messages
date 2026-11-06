# Networking

## Transports

| Transport | Status in this beta | Notes |
|---|---|---|
| **Bluetooth LE (GATT)** | **Implemented and used** | Works on almost all Android 7+ phones that support BLE. Range is about 10–50 m depending on walls, bodies and phone; throughput is roughly 5–40 KB/s depending on MTU and connection interval. |
| Wi-Fi Direct | Capability detected only | Not yet used for data. Planned for bulk file transfer. |
| Wi-Fi Aware | Capability detected only | Present only on some phones (Pixel, some Samsung). Not yet used. |

We say plainly what the beta does: **all traffic currently goes over BLE.** The Mesh screen shows which radios the phone supports, so users aren't misled.

## Discovery

- Each phone advertises (connectable) the service UUID plus 8 random bytes. They change every **15 minutes**, and also on every restart.
  - No device name and no Tx power are included.
- Each phone scans with a service-data filter.
- Entries are keyed by Bluetooth address, and a rotated id from the same device is merged into the same entry.
- A device that hasn't been seen for 30 seconds is removed from the list (unless it is connected).
- **Auto-connect** (can be turned off in Privacy):
  - The side with the lower ephemeral id dials immediately.
  - The other side waits 10 seconds to avoid both dialing at once.
  - At most **6** simultaneous links (Android's GATT limit is about 7).
- **QR connect.** Dials the device whose advertised id matches the scanned code, regardless of the auto-connect policy.

## Link setup

1. The central connects with `TRANSPORT_LE` and asks for high connection priority.
2. It requests MTU 517, then discovers services.
3. It enables notifications on TX by writing the CCCD. At that point both sides consider the link ready.
4. The engine handshake runs (see PROTOCOL_SPEC §2). If it doesn't authenticate within 20 seconds, the link is dropped.
5. Each direction is a strict FIFO queue: the next chunk is sent only after `onCharacteristicWrite` or `onNotificationSent` fires.

## Mesh routing

- **Approach:** controlled flooding with store-and-forward. With small, slowly changing neighbourhoods, this is the most robust option and leaks the least metadata, compared with routing tables, which would reveal the network topology.
- **Path choice:**
  - If the recipient is directly connected, the packet goes only to them.
  - Otherwise it goes to every neighbour that advertises the relay capability.
- **Loop prevention:** a seen-cache of packet ids (kept until the packet expires) plus a TTL hop limit (default 6, maximum 8).
- **Relay limits** (`RelayPolicy`):
  - 120 packets per minute per neighbour;
  - queue capped at 500 packets / 16 MB, oldest dropped first;
  - packets held at most 24 hours;
  - each packet forwarded at most 20 times;
  - paused below 20% battery unless the phone is charging;
  - the user can switch relaying off.
- **Retries:** exponential backoff from 15 seconds up to 10 minutes. A message is marked *failed* when it expires after 7 days.
- **Direct mode** always works, even with relaying disabled everywhere.

## Background behaviour

- The foreground service (type `connectedDevice`, with a persistent notification) keeps BLE running while the app is closed.
- Android Doze, App Standby and vendor battery managers (Xiaomi MIUI/HyperOS, Oppo/Realme ColorOS, Vivo, Huawei, Samsung "sleeping apps") **may still stop it**. The Troubleshooting screen links to the battery-optimisation settings.
- **We do not promise uninterrupted background communication.**

## Unsupported or limited devices

- **No BLE peripheral support** (`isMultipleAdvertisementSupported == false`): the phone can find others but can't be found. Others must be the ones to connect, or this phone scans their QR code.
- **Android < 7 (API 24):** not supported.
- **Android ≤ 11:** the OS requires the Location permission and Location services turned on for BLE scanning.
- **Emulators:** have no BLE, so they can't be used for transport testing.

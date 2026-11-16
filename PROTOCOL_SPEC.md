# Protocol specification (v1)

All integers are big-endian. `bytes16` means a u16 length followed by that many bytes. `bytes32` means a u32 length followed by the bytes. `str` is UTF-8 encoded as `bytes16`. Every decoder checks bounds and rejects trailing bytes.

## 1. Chunk (transport framing)

```
[ver u8 = 1][seq u16][index u16][count u16][data …]
```

- A link message is suffixed with a CRC32 and then split into chunks of at most `mtu − 3` bytes.
- The CRC only detects corruption. Authenticity comes from the layers above.
- **Reassembly limits:** at most 4 messages in flight, 96 KiB per message, 2048 chunks, and a 30-second timeout.

## 2. Link frames

| Type | Layout |
|---|---|
| `0x01 HELLO` | `ver u8, nonce[32], caps u16` (caps: `0x1` relay, `0x2` Wi-Fi Direct, `0x4` Wi-Fi Aware) |
| `0x02 AUTH` | `bytes16 identityKey, bytes16 bundle, bytes16 signature` |
| `0x03 AUTH_RESULT` | `code u8` (0 OK, 1 rejected, 2 blocked) |
| `0x10 DATA` | the raw packet that follows |
| `0x11 PING` | — |

**Bundle layout:**

```
regId u32, preKeyId u32, bytes16 preKey, spkId u32, bytes16 spk, bytes16 spkSig, kyberId u32, bytes16 kyberPub, bytes16 kyberSig
```

**AUTH signature:**

```
XEdDSA_identity("OfflineMesh-v1-link-auth" ‖ signerNonce ‖ otherNonce ‖ bytes16(identity) ‖ bytes32(bundle))
```

**Session initiation.** The side with the lexicographically lower contact id processes the peer's bundle and sends a `Profile` message right away, which is a PreKey message. The other side replies with its own `Profile`. If no session exists 8 seconds after the link authenticates, either side may initiate.

**Duplicate links.** When two links exist to the same peer, both sides keep the link initiated by the lower id. If both links have the same initiator, the newer one is kept.

## 3. Packet (relay-visible)

```
[ver u8=1][type u8=1][ttl u8][hops u8][packetId 16][routingTag 16][expiresAt u64 ms][bytes32 payload]
```

- **Limits:** payload ≤ 64 KiB; ttl and hops ≤ 8. Default TTL is 6. Expiry is the send time plus 7 days and is rejected if more than 8 days in the future.
- **AAD** = `ver ‖ type ‖ packetId ‖ routingTag ‖ expiresAt`. ttl and hops are excluded because relays change them.
- **routingTag** = `HMAC-SHA256(key = recipient identity key, "OfflineMesh-v1-route" ‖ u64(day))[0..16]`. Receivers accept the tags for day −1, 0 and +1.

## 4. Envelope

```
payload = HPKE-seal(recipientIdentityPub, info="OfflineMesh-v1-envelope", aad=AAD,
                    plaintext = bytes16 senderIdentity ‖ u8 signalType ‖ bytes32 ciphertext)
```

- `signalType` is the libsignal `CiphertextMessage` type: 2 = whisper, 3 = prekey, 7 = sender key.
- For prekey messages, the identity inside the message must equal `senderIdentity`.

## 5. Content (plaintext inside the ratchet)

| Tag | Content |
|---|---|
| 1 | `Text(str msgId, u64 sentAt, str text ≤ 8000)` |
| 2 | `Receipt(u8 kind[1=delivered, 2=read], u16 n ≤ 200, str msgId × n)` |
| 3 | `Profile(str name ≤ 64)` |
| 4 | `GroupOp(bytes32 signedGroupState)` |
| 5 | `SenderKey(str groupId, u32 epoch, bytes32 skdm)` |
| 6 | `GroupText(str groupId, u32 epoch, str msgId, u64 sentAt, str text)` — only through the group cipher |
| 7 | `Attachment(str groupId, u32 epoch, str msgId, u64 sentAt, str name, str mime, u64 size ≤ 8 MiB, bytes16 sha256, u32 chunkCount)` |
| 8 | `AttachmentChunk(str groupId, u32 epoch, str msgId, u32 index, bytes32 data ≤ 32 KiB)` |
| 9 | `QrClaim(bytes16 token[16])` |
| 10 | `Avatar(bytes32 image ≤ 64 KiB)`: JPEG or PNG profile photo (checked by magic bytes), empty = removed. Sent with `Profile` only when the photo changed since the last send to that contact. |

**Acknowledgement.** Every `Text`, `Attachment`, `AttachmentChunk`, `GroupText`, `GroupOp` and `SenderKey` is acknowledged with `Receipt(delivered, [ackId])`:

- `Text`, `GroupText` and `Attachment`: ackId = msgId.
- `AttachmentChunk`: ackId = `msgId#index`.
- `GroupOp` and `SenderKey`: ackId = packetId.

If a packet arrives again after it was already delivered, the receiver re-sends the same receipt.

## 6. Group state

```
body = u8 1, str groupId, u32 epoch, str name, u8 opType, str issuerId, u64 issuedAt,
       u16 n, (str id, bytes16 identityKey, str name) × n (sorted by id),
       u16 a, str adminId × a (sorted)
signed = bytes32 body ‖ bytes16 XEdDSA_issuer(body)
distributionId = UUIDv3(groupId + "/" + epoch)
```

- **Op types:** 1 create, 2 add, 3 remove, 4 leave, 5 admin, 6 rename.
- **Membership limit:** at most 64 members.
- **Transition rule:** see SECURITY.md.

## 7. QR code

```
"om1:" + base64url( u8 1 ‖ token[16] ‖ fingerprint[32] = SHA-256(identity) ‖ bleEphemeralId[8] ‖ u64 expiresAt )
```

- **Validity:** 5 minutes, single use.
- **Owner side:** the owner checks `QrClaim(token)` from the scanner inside the encrypted session.

## 8. BLE GATT

- **Service UUID:** `7b1e0001-4f3a-4c2e-9a51-0f5d6e8c1a20`. The advertisement carries service data under this UUID: an 8-byte ephemeral id.
- **RX** `…0002`: write with response, central → peripheral.
- **TX** `…0003`: notify, peripheral → central. The CCCD write is the point at which the link counts as ready.
- **MTU:** 517 is requested; the chunk size equals `mtu − 3`.

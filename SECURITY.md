# Security design

Offline Mesh does not implement any cryptographic primitive or protocol of its own. All messaging cryptography comes from **libsignal** (Signal's audited Rust implementation, version `0.106.2`). Hashing, HMAC and AES-GCM-at-rest come from the platform JCA and the Android Keystore.

## Identity

- On first launch the app generates a libsignal `IdentityKeyPair`: a Curve25519 key used for X25519 agreement and XEdDSA signatures. It also generates a registration id, a signed pre-key, a signed Kyber-1024 pre-key and 100 one-time pre-keys.
- **Contact id = SHA-256(identity public key).** The username is only a display label and is never used to identify anyone.
- **Fingerprint.** The first 20 bytes of that hash, shown as hex.
- **Safety number.** Signal's `NumericFingerprintGenerator`, version 2, with 5200 iterations. It is the same on both phones.

## Link authentication (transport handshake)

1. Each side sends `HELLO`, which carries a 32-byte random nonce.
2. Each side replies with `AUTH`. It contains:
   - the identity key;
   - a pre-key bundle;
   - an XEdDSA signature over `label ‖ myNonce ‖ peerNonce ‖ identity ‖ bundle`.
3. The receiver verifies the signature against the nonce it sent itself. That stops replayed `AUTH` frames, which `fakeIdentityHandshakeRejected` tests. The receiver also checks both pre-key signatures (libsignal checks them again later).

If any check fails, the link is dropped. **There is no unauthenticated or plaintext mode.**

> **Deviation from the original spec:** we don't use the Noise framework. libsignal's PQXDH + Double Ratchet *is* the reviewed authenticated key exchange, and adding a second, home-assembled Noise layer would not make it stronger. The signed nonce handshake above uses only libsignal's XEdDSA signatures.

## Message encryption

| Layer | Mechanism | Purpose |
|---|---|---|
| Content | libsignal `SessionCipher` (PQXDH + Double Ratchet, AES-256-CBC + HMAC-SHA256 inside libsignal) | Confidentiality, integrity, forward secrecy, post-compromise security, replay and duplicate rejection |
| Groups | libsignal `GroupCipher` (Sender Keys), with a distribution id per (group, epoch) | Encrypt once for many recipients |
| Envelope | libsignal `ECPublicKey.seal` (HPKE) to the recipient's identity key; the packet header (`ver, type, packetId, routingTag, expiresAt`) is the AAD | Hides the sender identity and message type from relays; binds the header so relays can't edit it |
| Routing | `HMAC-SHA256(recipientIdentityKey, label ‖ day)[0..16]` | Lets relays forward without a readable address |

- Every plaintext is encrypted before it reaches a transport.
- Names, receipts, group operations and QR claims are all `Content` inside the ratchet.
- Delivery and read receipts are themselves end-to-end encrypted messages from the recipient. **Nothing is ever marked delivered locally.**

## Groups

**Deviation from the original spec: no MLS.** There is no mature, vetted MLS implementation available on Android without a separate Rust toolchain. Groups use Signal **Sender Keys** plus **signed membership states**:

- Each change produces a full `GroupState` at `epoch + 1`. It is signed with the issuing admin's identity key.
- A member accepts a new state only if:
  - its signature is valid;
  - every member id equals `SHA-256(member key)`;
  - the epoch increases;
  - the issuer was an admin in the state the member currently holds. The one exception is a member removing only themselves (leave).
- Every epoch has a new distribution id, so every member creates a fresh sender key. Removed members never receive the new keys and **cannot decrypt later messages**, which `groupLifecycleAndRemovedMemberCannotRead` tests.
- A group message is accepted only if it arrives through the group cipher, comes from a current member, and is for the current epoch.
- **Limitations compared with MLS:**
  - Sender Keys give forward secrecy per sender chain, but post-compromise security only at epoch changes.
  - Two admins changing the group at the same moment can produce two states with the same epoch number; the first one received wins. See THREAT_MODEL.md.

## Key storage

- **Signal protocol state** (identity, pre-keys, sessions, sender keys) is stored only as serialized libsignal records in the `crypto_kv` table of the **SQLCipher** database (AES-256, `secure_delete=ON`).
- **The database passphrase** is 32 random bytes. It is wrapped with AES-256-GCM under a non-exportable **Android Keystore** key, StrongBox-backed when the phone has StrongBox, otherwise TEE or software. The wrapped blob sits in `noBackupFilesDir`.
- **Attachments** are AES-256-GCM encrypted with a second Keystore-wrapped key. Each chunk and each file has its own random 96-bit nonce, and its id is used as AAD.
- **Backups are disabled:** `allowBackup=false`, and the `dataExtractionRules` exclude everything.
- **Private keys never reach JavaScript.** The bridge returns only public data: names, fingerprints, statuses and message text the user is meant to see.
- **Logs** contain only exception class names, never keys or plaintext.
- **No hardcoded secrets.** The BLE service UUID is public by design.

## Verification and identity warnings

- **QR pairing.** The QR code holds a one-time 16-byte token, the identity fingerprint, the current BLE ephemeral id and an expiry 5 minutes ahead. It contains **no private keys**.
  - The scanner connects, then checks that the peer's identity hash equals the fingerprint in the QR, and marks the peer verified.
  - The scanner then sends the token back inside the encrypted session. The code owner checks the token is one it issued, unexpired and unused, and marks the scanner verified. That makes verification mutual.
  - Reused or expired tokens are rejected and logged (`qrVerificationMutualAndTokenSingleUse`).
- **Identity warnings.** Because identity is the key, a "changed key" shows up as a new contact. If a new key arrives using the same name as an existing contact, the app shows an **identity warning** and asks the user to verify. It does this instead of silently trusting the name.
- **Device revocation** deletes the sessions for that key and resets its verification.

## Things we deliberately don't do

- No internet fallback. Release builds have no `INTERNET` permission.
- No plaintext fallback and no encryption downgrade.
- No custom ciphers, KDFs or protocols.

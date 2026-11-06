# Threat model

## Assets
1. Message content: text, files, and group content.
2. Identity private keys and session state.
3. Social graph: who talks to whom, and group membership.
4. User location and presence.

## Adversaries and mitigations

| Adversary | Capability | Mitigation | Residual risk |
|---|---|---|---|
| Passive radio eavesdropper | Records all BLE traffic nearby | Ratchet ciphertext inside an HPKE envelope; names travel only inside the ratchet | Sees the identity *public* key during the link handshake, plus traffic volume and timing |
| Active MITM in radio range | Relays or alters handshake frames | Nonce-bound XEdDSA link auth; libsignal verifies signed pre-keys; safety numbers and QR verification | An **unverified** first contact can't be told apart from an impersonator who has never been seen before. Verify in person. |
| Impersonator using a known name | Creates a new identity with your friend's name | Contacts are keyed by key hash; the same name with a different key raises an identity warning | User ignores the warning |
| Malicious relay | Drops, delays, replays or modifies packets | AAD binds the header; ratchet MAC; packet-id seen cache; ratchet duplicate detection; expiry | Can drop packets (denial of service) and observe the routing tag, size and timing |
| Replay attacker | Re-injects old packets or QR codes | Seen cache, `DuplicateMessageException`, single-use QR tokens, expiry | — |
| Flooding attacker | Sends lots of junk | Size limits on every field; per-neighbour rate limit; bounded relay queue; malformed input dropped without crashing | Can fill the 16 MB relay queue, which then evicts the oldest packets |
| Removed group member | Keeps old keys | New epoch means a new sender key that is never sent to them | Can still read messages from before removal (by design) |
| Rogue group member | Forges membership changes | Changes must be signed by an admin of the current state; `isValidTransition` | Two admins acting at the same time: the first state received wins |
| Phone thief (locked phone, data extracted) | Reads the app's private storage | SQLCipher database plus AES-GCM files; keys wrapped by a hardware-backed Keystore when available | On a rooted or unlocked running phone, the Keystore key can be *used* by an attacker running as the app |
| Database copied off the phone | Has `mesh.db` without the Keystore | Unreadable without the wrapped key, which can't be exported from the Keystore | — |
| Cloud backup | Restores the data elsewhere | Backups are disabled | — |
| Tracker | Follows a BLE advertiser | Id rotates every 15 min; no name, number or key in the advertisement; the user can turn visibility off | The OS Bluetooth MAC randomization period varies by vendor |

## Metadata exposure (honest summary)

- **Relays see:** a 16-byte routing tag that is the same for one recipient for one day, packet size, hop count and timing.
  - Anyone who already knows the recipient's public key can compute that tag.
  - Relays never learn the sender: the sender's identity is inside the HPKE envelope.
- **Direct peers see:** your identity public key and the display name you chose.
- **Out of scope:** traffic-analysis resistance (padding or cover traffic), and a compromised OS or malware with root.

## Assumptions

- libsignal, BoringSSL/RustCrypto inside it, and the Android Keystore are correct.
- Users who need protection against impersonation verify their contacts in person.
- The OS enforces app sandboxing.

# Database schema

- **Engine:** SQLCipher 4 (`net.zetetic:sqlcipher-android`). The file is `noBackupFilesDir/mesh.db`.
- **Key:** 32 random bytes, wrapped by the Android Keystore (see SECURITY.md).
- **Migrations:** live in `android/core/src/main/kotlin/com/offlinemesh/core/db/Schema.kt`. The `schema_version` table records how far they have run. **Append a new version; never edit a version that has shipped.**

## V1 tables

| Table | Purpose | Notes |
|---|---|---|
| `crypto_kv(ns, k, v)` | libsignal state | namespaces: `self` (identity key pair, registration id), `identity`, `prekey`, `signed_prekey`, `kyber_prekey`, `session`, `sender_key`. **Only serialized libsignal records.** |
| `contacts` | Known devices (1 contact = 1 identity key) | `id` = SHA-256(identity key) in hex; `verified`, `verified_at`, `blocked`, `identity_changed` (name-collision warning), `first_seen`, `last_seen` |
| `conversations` | Chat list | `d:<contactId>` or `g:<groupId>`; title, preview, unread count |
| `messages` | Message history | status: pending/sent/delivered/read/failed/received/receiving |
| `message_receipts` | Receipt status per recipient | used for group delivery and read state |
| `attachments`, `attachment_chunks` | File metadata and progress | the bytes live in encrypted files, not in the database |
| `groups`, `group_members`, `group_epochs` | Current signed state, membership, and the history of signed states | |
| `group_invites` | Invitations not yet accepted | |
| `pending_sender_keys` | Sender keys that arrived before the invite was accepted or the epoch applied | bounded at 500 |
| `outbox` | Encrypted packets waiting for a receipt | holds sealed ciphertext only |
| `relay_queue` | Other people's sealed packets being carried | bounded; 24 h maximum |
| `seen_packets` | Replay and loop cache | kept until each packet expires |
| `security_events` | Security log | last 500 |
| `settings` | Preferences | |
| `used_qr_tokens` | QR replay protection | |

## V2 (app 0.1.5)

| Change | Purpose |
|---|---|
| `conversations.pinned` | Pinned chats are listed first |
| `contacts.avatar` | Contact's profile photo (small JPEG/PNG received over the E2E session) |

Your own photo is kept in `settings` (`my_avatar`), inside the same encrypted database. Upgrading from V1 keeps all data (`FeaturesTest.upgradeFromV1KeepsDataAndAddsColumns`).

## Data minimisation

- **Not stored:** phone numbers, emails, location, or Bluetooth addresses of peers. Nearby discovery state is kept only in memory.
- **Deleting a message** removes the row, its receipts, any outbox entries and the encrypted file.
- **Erase everything** uses `clearApplicationUserData()`, which removes the database, the wrapped keys and all files.

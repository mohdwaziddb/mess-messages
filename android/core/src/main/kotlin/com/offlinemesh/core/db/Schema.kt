package com.offlinemesh.core.db

/**
 * Versioned migrations. Append new versions; never edit an existing one.
 * The whole database is SQLCipher-encrypted on device (see DATABASE_SCHEMA.md).
 */
object Schema {
    val migrations: List<List<String>> = listOf(
        // V1
        listOf(
            // libsignal protocol state (identity key pair, prekeys, sessions, sender keys), serialized records only.
            "CREATE TABLE crypto_kv (ns TEXT NOT NULL, k TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY (ns, k))",
            """CREATE TABLE contacts (
                id TEXT PRIMARY KEY, identity_key BLOB NOT NULL, name TEXT NOT NULL DEFAULT '',
                verified INTEGER NOT NULL DEFAULT 0, verified_at INTEGER, blocked INTEGER NOT NULL DEFAULT 0,
                identity_changed INTEGER NOT NULL DEFAULT 0, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)""",
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, peer_id TEXT, group_id TEXT, title TEXT NOT NULL DEFAULT '',
                last_msg_at INTEGER NOT NULL DEFAULT 0, last_preview TEXT NOT NULL DEFAULT '', unread INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY, conv_id TEXT NOT NULL, sender_id TEXT NOT NULL, outgoing INTEGER NOT NULL,
                kind TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL)""",
            "CREATE INDEX idx_messages_conv ON messages(conv_id, sent_at)",
            """CREATE TABLE message_receipts (msg_id TEXT NOT NULL, member_id TEXT NOT NULL, status TEXT NOT NULL,
                at INTEGER NOT NULL, PRIMARY KEY (msg_id, member_id))""",
            """CREATE TABLE attachments (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 BLOB NOT NULL,
                chunk_count INTEGER NOT NULL, received_chunks INTEGER NOT NULL DEFAULT 0, file_key BLOB,
                path TEXT, complete INTEGER NOT NULL DEFAULT 0)""",
            "CREATE TABLE attachment_chunks (att_id TEXT NOT NULL, idx INTEGER NOT NULL, PRIMARY KEY (att_id, idx))",
            """CREATE TABLE groups (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, epoch INTEGER NOT NULL, state BLOB NOT NULL,
                active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)""",
            """CREATE TABLE group_members (group_id TEXT NOT NULL, member_id TEXT NOT NULL, is_admin INTEGER NOT NULL,
                PRIMARY KEY (group_id, member_id))""",
            """CREATE TABLE group_epochs (group_id TEXT NOT NULL, epoch INTEGER NOT NULL, op BLOB NOT NULL,
                applied_at INTEGER NOT NULL, PRIMARY KEY (group_id, epoch))""",
            """CREATE TABLE group_invites (group_id TEXT PRIMARY KEY, from_id TEXT NOT NULL, op BLOB NOT NULL,
                received_at INTEGER NOT NULL)""",
            """CREATE TABLE outbox (
                packet_id TEXT PRIMARY KEY, dest_id TEXT NOT NULL, msg_id TEXT, packet BLOB NOT NULL,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt INTEGER NOT NULL DEFAULT 0, needs_ack INTEGER NOT NULL DEFAULT 1)""",
            "CREATE INDEX idx_outbox_dest ON outbox(dest_id)",
            """CREATE TABLE relay_queue (
                packet_id TEXT PRIMARY KEY, routing_tag TEXT NOT NULL, packet BLOB NOT NULL, from_link TEXT NOT NULL,
                received_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, forwards INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE seen_packets (packet_id TEXT PRIMARY KEY, seen_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                ack_dest TEXT, ack_msg_id TEXT)""",
            """CREATE TABLE security_events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL,
                contact_id TEXT, detail TEXT NOT NULL DEFAULT '')""",
            "CREATE TABLE settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)",
            """CREATE TABLE pending_sender_keys (group_id TEXT NOT NULL, sender_id TEXT NOT NULL, epoch INTEGER NOT NULL,
                dist BLOB NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (group_id, sender_id))""",
            "CREATE TABLE used_qr_tokens (token TEXT PRIMARY KEY, used_at INTEGER NOT NULL)",
        ),
        // V2: pinned chats, contact profile photos.
        listOf(
            "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE contacts ADD COLUMN avatar BLOB",
        ),
    )

    fun migrate(db: Sql) {
        db.exec("CREATE TABLE IF NOT EXISTS schema_version (v INTEGER NOT NULL)")
        val current = db.one("SELECT v FROM schema_version")?.int("v") ?: 0
        if (current == 0 && db.one("SELECT v FROM schema_version") == null) db.exec("INSERT INTO schema_version (v) VALUES (0)")
        for (i in current until migrations.size) {
            db.tx {
                migrations[i].forEach { db.exec(it) }
                db.exec("UPDATE schema_version SET v = ?", i + 1)
            }
        }
    }

    val version get() = migrations.size
}

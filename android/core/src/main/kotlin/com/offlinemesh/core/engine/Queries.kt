package com.offlinemesh.core.engine

import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.db.one
import org.signal.libsignal.protocol.IdentityKey

/** Read-only views for the UI. Only public data leaves the engine (never key material). */

fun Engine.profile(): Map<String, Any?> = mapOf(
    "id" to myId,
    "name" to myName,
    "fingerprint" to Crypto.displayFingerprint(myIdentity),
    "createdAt" to db.one("SELECT MIN(at) AS t FROM security_events WHERE kind='identity_created'")?.longOrNull("t"),
)

fun Engine.contacts(): List<Map<String, Any?>> {
    val online = connectedPeerIds().toSet()
    return db.query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE").map {
        mapOf(
            "id" to it.str("id"),
            "name" to it.str("name").ifEmpty { "Unknown" },
            "verified" to it.bool("verified"),
            "verifiedAt" to it.longOrNull("verified_at"),
            "blocked" to it.bool("blocked"),
            "identityWarning" to it.bool("identity_changed"),
            "lastSeen" to it.long("last_seen"),
            "firstSeen" to it.long("first_seen"),
            "connected" to (it.str("id") in online),
            "fingerprint" to Crypto.displayFingerprint(IdentityKey(it.blob("identity_key"))),
        )
    }
}

fun Engine.conversations(): List<Map<String, Any?>> {
    val online = connectedPeerIds().toSet()
    return db.query("""SELECT c.*, ct.verified AS verified, ct.identity_changed AS warn, g.active AS gactive
        FROM conversations c LEFT JOIN contacts ct ON ct.id = c.peer_id LEFT JOIN groups g ON g.id = c.group_id
        ORDER BY c.last_msg_at DESC""").map {
        mapOf(
            "id" to it.str("id"),
            "kind" to it.str("kind"),
            "peerId" to it.strOrNull("peer_id"),
            "groupId" to it.strOrNull("group_id"),
            "title" to it.str("title").ifEmpty { "Unknown" },
            "lastMsgAt" to it.long("last_msg_at"),
            "preview" to it.str("last_preview"),
            "unread" to it.int("unread"),
            "verified" to it.bool("verified"),
            "identityWarning" to it.bool("warn"),
            "connected" to (it.strOrNull("peer_id") in online),
            "active" to (it.str("kind") == "direct" || it.bool("gactive")),
        )
    }
}

fun Engine.messages(convId: String, limit: Int = 200, beforeTs: Long = Long.MAX_VALUE): List<Map<String, Any?>> {
    val names = db.query("SELECT id, name FROM contacts").associate { it.str("id") to it.str("name") }
    return db.query("""SELECT m.*, a.mime AS mime, a.size AS size, a.complete AS complete, a.received_chunks AS rc, a.chunk_count AS cc
        FROM messages m LEFT JOIN attachments a ON a.id = m.id
        WHERE m.conv_id=? AND m.sent_at < ? ORDER BY m.sent_at DESC, m.received_at DESC LIMIT ?""", convId, beforeTs, limit).map {
        mapOf(
            "id" to it.str("id"),
            "senderId" to it.str("sender_id"),
            "senderName" to (if (it.bool("outgoing")) "You" else names[it.str("sender_id")] ?: ""),
            "outgoing" to it.bool("outgoing"),
            "kind" to it.str("kind"),
            "body" to it.str("body"),
            "status" to it.str("status"),
            "sentAt" to it.long("sent_at"),
            "receivedAt" to it.long("received_at"),
            "mime" to it.strOrNull("mime"),
            "size" to it.longOrNull("size"),
            "complete" to it.bool("complete"),
            "progress" to if (it.int("cc") > 0) it.int("rc").toDouble() / it.int("cc") else null,
        )
    }.reversed()
}

fun Engine.groups(): List<Map<String, Any?>> = db.query("SELECT * FROM groups ORDER BY name COLLATE NOCASE").map { row ->
    val g = GroupState.decode(row.blob("state"))
    mapOf(
        "id" to g.groupId, "convId" to "g:${g.groupId}", "name" to g.name, "epoch" to g.epoch,
        "active" to row.bool("active"), "isAdmin" to g.isAdmin(myId), "memberCount" to g.members.size,
    )
}

fun Engine.groupDetails(groupId: String): Map<String, Any?>? {
    val g = groupState(groupId) ?: return null
    val contacts = db.query("SELECT id, verified, name FROM contacts").associateBy { it.str("id") }
    val online = connectedPeerIds().toSet()
    return mapOf(
        "id" to g.groupId, "name" to g.name, "epoch" to g.epoch, "isAdmin" to g.isAdmin(myId), "isMember" to g.isMember(myId),
        "lastChange" to g.issuedAt,
        "members" to g.members.map { m ->
            mapOf(
                "id" to m.id, "name" to if (m.id == myId) "You" else (contacts[m.id]?.str("name")?.ifEmpty { null } ?: m.name),
                "isAdmin" to (m.id in g.admins), "isMe" to (m.id == myId),
                "verified" to (contacts[m.id]?.bool("verified") ?: (m.id == myId)), "connected" to (m.id in online),
            )
        },
    )
}

fun Engine.invites(): List<Map<String, Any?>> = db.query("SELECT * FROM group_invites ORDER BY received_at DESC").map {
    val g = GroupState.decode(it.blob("op"))
    mapOf("groupId" to g.groupId, "name" to g.name, "from" to (db.one("SELECT name FROM contacts WHERE id=?", it.str("from_id"))?.str("name") ?: ""),
        "memberCount" to g.members.size, "receivedAt" to it.long("received_at"))
}

fun Engine.securityEvents(limit: Int = 100): List<Map<String, Any?>> =
    db.query("SELECT * FROM security_events ORDER BY id DESC LIMIT ?", limit).map {
        mapOf("id" to it.long("id"), "at" to it.long("at"), "kind" to it.str("kind"), "contactId" to it.strOrNull("contact_id"), "detail" to it.str("detail"))
    }

fun Engine.storageInfo(): Map<String, Any?> = mapOf(
    "messages" to db.one("SELECT COUNT(*) AS n FROM messages")!!.int("n"),
    "attachments" to db.one("SELECT COUNT(*) AS n FROM attachments")!!.int("n"),
    "attachmentBytes" to db.one("SELECT COALESCE(SUM(size),0) AS n FROM attachments WHERE complete=1")!!.long("n"),
    "outbox" to db.one("SELECT COUNT(*) AS n FROM outbox")!!.int("n"),
    "relayQueue" to db.one("SELECT COUNT(*) AS n FROM relay_queue")!!.int("n"),
    "relayBytes" to db.one("SELECT COALESCE(SUM(length(packet)),0) AS n FROM relay_queue")!!.long("n"),
)

fun Engine.clearRelayQueue() = db.exec("DELETE FROM relay_queue")

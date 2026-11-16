package com.offlinemesh.core.engine

import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.crypto.SignalStore
import com.offlinemesh.core.db.Sql
import com.offlinemesh.core.db.one
import com.offlinemesh.core.mesh.RelayPolicy
import com.offlinemesh.core.proto.Chunker
import com.offlinemesh.core.proto.Content
import com.offlinemesh.core.proto.Hex
import com.offlinemesh.core.proto.LinkFrame
import com.offlinemesh.core.proto.MalformedException
import com.offlinemesh.core.proto.Packet
import com.offlinemesh.core.proto.Reassembler
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.UUID

/** A direct transport connection (BLE GATT, Wi-Fi Direct socket, in-memory in tests). */
interface Link {
    val id: String
    /** Max bytes per chunk the transport can carry in one write. */
    val maxChunk: Int
    /** True if this side initiated the connection (BLE central). Used for deterministic de-duplication. */
    val initiator: Boolean get() = false
    fun send(chunk: ByteArray)
    fun close()
}

fun interface EngineListener {
    /** kind: contacts | conversations | messages | groups | security | links | stats. id optional. */
    fun onChange(kind: String, id: String?)
}

/** Encrypted file storage for attachments (Android: per-file AES-GCM; tests: memory). */
interface BlobStore {
    fun writeChunk(attId: String, index: Int, data: ByteArray)
    fun assemble(attId: String, count: Int)
    fun read(attId: String): ByteArray?
    fun delete(attId: String)
}

class EngineException(msg: String) : Exception(msg)

/**
 * The protocol engine. NOT thread-safe: the host must call every method from one thread
 * (Android: a single-threaded executor inside MeshService).
 *
 * Layering: transport chunks → [LinkFrame] → [Packet] (relay-visible header) → sealed envelope
 * (HPKE to recipient identity) → libsignal Double Ratchet / Sender Key ciphertext → [Content].
 */
class Engine(
    val db: Sql,
    private val blobs: BlobStore,
    private val listener: EngineListener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val store = SignalStore(db)
    val relay = RelayPolicy(clock)

    private class LinkState(val link: Link) {
        val nonce: ByteArray = Crypto.randomBytes(32)
        var peerNonce: ByteArray? = null
        var peerId: String? = null
        var peerIdentity: IdentityKey? = null
        var peerBundle: org.signal.libsignal.protocol.state.PreKeyBundle? = null
        var authedAt = 0L
        var sentAuth = false
        var peerCaps = 0
        var seq = 0
        val reasm = Reassembler()
        val openedAt = System.currentTimeMillis()
    }

    private val links = LinkedHashMap<String, LinkState>()
    private val issuedQr = HashMap<String, Long>()                 // token hex -> expiresAt
    private val pendingQrVerify = HashMap<String, String>()        // expected contactId -> token hex
    private val pendingGroupPackets = ArrayList<Pair<Long, ByteArray>>() // sender-key msgs waiting for SKDM

    /** Optional diagnostics sink. Lines carry ids (shortened), link ids and counts only: never content or keys. */
    var logger: ((String) -> Unit)? = null
    private fun log(msg: String) { logger?.let { l -> runCatching { l(msg) } } }
    private fun short(id: String?) = id?.take(8) ?: "-"

    var relayEnabled: Boolean
        get() = setting("relay_enabled", "1") == "1"
        set(v) { putSetting("relay_enabled", if (v) "1" else "0") }

    // ================================================================== identity / profile

    fun hasIdentity() = store.hasLocalIdentity()

    fun createIdentity(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 32) throw EngineException("Name must be 1–32 characters")
        Crypto.createIdentity(store)
        putSetting("my_name", n)
        securityEvent("identity_created", null, "Local identity keys generated")
    }

    val myIdentity: IdentityKey get() = store.identityKeyPair.publicKey
    val myId: String get() = Crypto.contactId(myIdentity)
    val myName: String get() = setting("my_name", "")
    private val myAddr get() = SignalProtocolAddress(myId, Crypto.DEVICE_ID)

    fun setMyName(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 32) throw EngineException("Name must be 1–32 characters")
        putSetting("my_name", n)
        authedLinks().forEach { sendContent(it.peerId!!, Content.Profile(n), ack = false) }
    }

    // ------------------------------------------------------------------ profile photo

    /** My profile photo (small JPEG/PNG), or null. Stored inside the encrypted database. */
    val myAvatar: ByteArray?
        get() = setting("my_avatar", "").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) }

    /** Set (or with null, remove) my photo and push it to everyone connected right now; others get it on next connect. */
    fun setMyAvatar(image: ByteArray?) {
        if (image != null) {
            if (image.size > Content.MAX_AVATAR) throw EngineException("Photo too large")
            if (!Content.isImage(image)) throw EngineException("Unsupported image")
        }
        putSetting("my_avatar", image?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "")
        putSetting("my_avatar_ver", UUID.randomUUID().toString())
        authedLinks().forEach { st -> runCatching { sendProfile(st.peerId!!) } }
        changed("contacts", myId)
    }

    /** Name always; photo only when this peer hasn't received the current version yet. */
    private fun sendProfile(peerId: String) {
        sendContent(peerId, Content.Profile(myName), ack = false)
        val ver = setting("my_avatar_ver", "")
        if (ver.isNotEmpty() && setting("avatar_sent:$peerId", "") != ver) {
            sendContent(peerId, Content.Avatar(myAvatar ?: ByteArray(0)), ack = false)
            putSetting("avatar_sent:$peerId", ver)
        }
    }

    // ------------------------------------------------------------------ chat list

    fun setPinned(convId: String, pinned: Boolean) {
        db.one("SELECT id FROM conversations WHERE id=?", convId) ?: throw EngineException("No conversation")
        db.exec("UPDATE conversations SET pinned=? WHERE id=?", if (pinned) 1 else 0, convId)
        changed("conversations", convId)
    }

    // ================================================================== link lifecycle

    fun linkUp(link: Link) {
        val st = LinkState(link)
        links[link.id] = st
        log("link up ${link.id} initiator=${link.initiator} chunk=${link.maxChunk}")
        val caps = (if (relayEnabled) LinkFrame.CAP_RELAY else 0)
        sendFrame(st, LinkFrame.Hello(LinkFrame.PROTOCOL_VERSION, st.nonce, caps))
    }

    fun linkDown(linkId: String) {
        val st = links.remove(linkId) ?: return
        log("link down $linkId peer=${short(st.peerId)}")
        if (st.peerId != null) changed("links", st.peerId)
    }

    fun linkChunk(linkId: String, chunk: ByteArray) {
        val st = links[linkId] ?: return
        val msg = try { st.reasm.accept(chunk) ?: return } catch (e: MalformedException) { return }
        val frame = try { LinkFrame.decode(msg) } catch (e: MalformedException) { return }
        try {
            onFrame(st, frame)
        } catch (e: Exception) {
            // Any handshake failure tears down the link: never fall back to an unauthenticated mode.
            log("frame error on ${st.link.id}: ${e.javaClass.simpleName}: ${e.message}")
            if (st.peerId == null) { securityEvent("handshake_failed", null, e.message ?: "error"); drop(st) }
        }
    }

    /** Drops links whose handshake didn't finish in time. */
    private fun reapStaleLinks() {
        val now = System.currentTimeMillis()
        links.values.filter { it.peerId == null && now - it.openedAt > 20_000 }.forEach { drop(it) }
        // If the designated initiator never started a session (e.g. it lost state), start one ourselves.
        links.values.filter { it.peerId != null && now - it.authedAt > 8_000 }.forEach { st ->
            val addr = SignalProtocolAddress(st.peerId!!, Crypto.DEVICE_ID)
            val b = st.peerBundle
            if (!store.containsSession(addr) && b != null) runCatching {
                SessionBuilder(store, addr, myAddr).process(b)
                st.peerBundle = null
                sendProfile(st.peerId!!)
            }
        }
    }

    private fun drop(st: LinkState) {
        links.remove(st.link.id)
        runCatching { st.link.close() }
    }

    private fun sendFrame(st: LinkState, f: LinkFrame) {
        val bytes = LinkFrame.encode(f)
        Chunker.split(st.seq++, bytes, st.link.maxChunk).forEach { st.link.send(it) }
    }

    private fun onFrame(st: LinkState, f: LinkFrame) {
        when (f) {
            is LinkFrame.Hello -> {
                if (f.version != LinkFrame.PROTOCOL_VERSION) throw EngineException("protocol version ${f.version}")
                if (st.peerNonce != null) return
                st.peerNonce = f.nonce
                st.peerCaps = f.caps
                val bundle = Crypto.localBundle(store)
                val sig = Crypto.signLinkAuth(store, st.nonce, f.nonce, bundle)
                sendFrame(st, LinkFrame.Auth(myIdentity.serialize(), bundle, sig))
                st.sentAuth = true
            }
            is LinkFrame.Auth -> onAuth(st, f)
            is LinkFrame.AuthResult -> if (f.code != LinkFrame.AUTH_OK) drop(st)
            is LinkFrame.Data -> { if (st.peerId != null) onPacket(st, f.packet) }
            LinkFrame.Ping -> Unit
        }
    }

    private fun onAuth(st: LinkState, f: LinkFrame.Auth) {
        val peerNonce = st.peerNonce ?: throw EngineException("auth before hello")
        if (st.peerId != null) return
        val identity = IdentityKey(f.identityKey)
        if (!Crypto.verifyLinkAuth(identity, peerNonce, st.nonce, f.bundle, f.signature)) throw EngineException("bad link signature")
        val peerId = Crypto.contactId(identity)
        if (peerId == myId) throw EngineException("connected to self")
        val bundle = Crypto.parseBundle(f.bundle, identity)

        if (isBlocked(peerId)) {
            sendFrame(st, LinkFrame.AuthResult(LinkFrame.AUTH_BLOCKED)); drop(st); return
        }
        // Duplicate connection prevention: keep exactly one link per peer. Both sides pick the same one:
        // the link initiated by the lower id (if roles differ), otherwise the newest.
        links.values.firstOrNull { it !== st && it.peerId == peerId }?.let { other ->
            val preferInitiatedByMe = myId < peerId
            if (st.link.initiator != other.link.initiator && other.link.initiator == preferInitiatedByMe) {
                log("duplicate link to ${short(peerId)}: keep ${other.link.id}, drop ${st.link.id}")
                drop(st); return
            }
            log("duplicate link to ${short(peerId)}: keep ${st.link.id}, drop ${other.link.id}")
            drop(other)
        }

        st.peerId = peerId
        st.peerIdentity = identity
        st.peerBundle = bundle
        st.authedAt = System.currentTimeMillis()
        log("authenticated ${st.link.id} peer=${short(peerId)}")
        val isNew = upsertContact(peerId, identity)

        val peerAddr = SignalProtocolAddress(peerId, Crypto.DEVICE_ID)
        val haveSession = store.containsSession(peerAddr)
        // Deterministic initiator avoids simultaneous-initiation churn: lower id builds the session.
        if (!haveSession && myId < peerId) {
            SessionBuilder(store, peerAddr, myAddr).process(bundle)
        }
        if (store.containsSession(peerAddr)) sendProfile(peerId)

        pendingQrVerify.remove(peerId)?.let { token -> completeQrVerification(peerId, token) }
        if (isNew) securityEvent("new_contact", peerId, "First secure connection; identity not yet verified")
        changed("links", peerId); changed("contacts", peerId)
        flushFor(st)
    }

    fun connectedPeerIds(): List<String> = authedLinks().map { it.peerId!! }
    private fun authedLinks() = links.values.filter { it.peerId != null }
    private fun linkFor(peerId: String) = links.values.firstOrNull { it.peerId == peerId }

    // ================================================================== sending

    private fun peerIdentity(peerId: String): IdentityKey =
        db.one("SELECT identity_key FROM contacts WHERE id=?", peerId)?.let { IdentityKey(it.blob("identity_key")) }
            ?: throw EngineException("unknown contact")

    /**
     * Encrypt [content] for [peerId] with the pairwise Double Ratchet session and queue it.
     * Returns the ack id (message id for texts, packet id otherwise).
     */
    private fun sendContent(peerId: String, content: Content, ack: Boolean = true, ackId: String? = null): String {
        val addr = SignalProtocolAddress(peerId, Crypto.DEVICE_ID)
        if (!store.containsSession(addr)) throw EngineException("No secure session with this contact yet. Connect nearby once first.")
        val ct = SessionCipher(store, myAddr, addr).encrypt(Content.encode(content))
        return queuePacket(peerId, ct.type, ct.serialize(), ack, ackId)
    }

    private fun queuePacket(peerId: String, signalType: Int, ciphertext: ByteArray, ack: Boolean, ackId: String?, ttlMs: Long = MESSAGE_TTL_MS): String {
        val recipient = peerIdentity(peerId)
        val now = clock()
        val header = Packet(Packet.TYPE_ENVELOPE, Packet.DEFAULT_TTL, 0, Crypto.randomBytes(16),
            Crypto.routingTag(recipient, Crypto.dayIndex(now)), now + ttlMs, ByteArray(0))
        val packet = header.copy(payload = Crypto.sealEnvelope(recipient, myIdentity, signalType, ciphertext, header.aad()))
        val id = ackId ?: packet.idHex()
        db.exec("INSERT INTO outbox (packet_id, dest_id, msg_id, packet, created_at, expires_at, needs_ack) VALUES (?,?,?,?,?,?,?)",
            packet.idHex(), peerId, id, packet.encode(), now, packet.expiresAt, ack)
        transmit(packet.idHex())
        return id
    }

    /** Try to send one outbox packet now: directly if connected, otherwise via relays (flood). */
    private fun transmit(packetId: String): Boolean {
        val row = db.one("SELECT * FROM outbox WHERE packet_id=?", packetId) ?: return false
        val dest = row.str("dest_id")
        val direct = linkFor(dest)
        val targets = if (direct != null) listOf(direct) else authedLinks().filter { it.peerCaps and LinkFrame.CAP_RELAY != 0 }
        if (targets.isEmpty()) {
            log("transmit ${packetId.take(8)} to ${short(dest)}: no path (links=${links.size}, authed=${authedLinks().map { short(it.peerId) }})")
            return false
        }
        val bytes = row.blob("packet")
        // One broken link must not stop the others or leave the outbox row untouched.
        val sentOn = targets.count { st ->
            runCatching { sendFrame(st, LinkFrame.Data(bytes)) }
                .onFailure { log("send on ${st.link.id} failed: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
        }
        log("transmit ${packetId.take(8)} to ${short(dest)}: ${if (direct != null) "direct" else "relay"} ok=$sentOn/${targets.size}")
        if (sentOn == 0) return false
        if (!row.bool("needs_ack") && direct != null) {
            db.exec("DELETE FROM outbox WHERE packet_id=?", packetId)
        } else {
            db.exec("UPDATE outbox SET attempts=attempts+1, last_attempt=? WHERE packet_id=?", clock(), packetId)
        }
        return true
    }

    private fun flushFor(st: LinkState) {
        val peer = st.peerId ?: return
        val queued = db.query("SELECT packet_id, msg_id FROM outbox WHERE dest_id=? ORDER BY created_at", peer)
        queued.forEach { transmit(it.str("packet_id")) }
        // Show the new state (clock -> tick) right away instead of on the next 10 s tick.
        queued.mapNotNull { it.strOrNull("msg_id")?.take(36) }.distinct().forEach { runCatching { refreshStatus(it) } }
        // Store-and-forward: hand queued relay packets to the new neighbour.
        if (relayEnabled) relay.queued(db, clock()).forEach { (pid, bytes, from) ->
            if (from != st.link.id) { sendFrame(st, LinkFrame.Data(bytes)); relay.markForwarded(db, pid) }
        }
    }

    // ================================================================== public messaging API

    fun sendText(convId: String, text: String): String {
        val body = text.trim()
        if (body.isEmpty()) throw EngineException("Empty message")
        if (body.length > Content.MAX_TEXT) throw EngineException("Message too long")
        val conv = db.one("SELECT * FROM conversations WHERE id=?", convId) ?: throw EngineException("No conversation")
        val msgId = UUID.randomUUID().toString()
        val now = clock()
        if (conv.str("kind") == "group") {
            val g = groupState(conv.str("group_id")) ?: throw EngineException("Group missing")
            requireActiveMember(g)
            insertMessage(msgId, convId, myId, true, "text", body, "pending", now)
            sendGroupContent(g, Content.GroupText(g.groupId, g.epoch, msgId, now, body), msgId)
        } else {
            val peer = conv.str("peer_id")
            if (isBlocked(peer)) throw EngineException("Contact is blocked")
            insertMessage(msgId, convId, myId, true, "text", body, "pending", now)
            sendContent(peer, Content.Text(msgId, now, body), ackId = msgId)
            refreshStatus(msgId)
        }
        return msgId
    }

    /** Re-send a failed message with fresh encryption (same message id). */
    fun retryMessage(msgId: String) {
        val m = db.one("SELECT * FROM messages WHERE id=? AND outgoing=1", msgId) ?: throw EngineException("No message")
        if (m.str("status") != "failed") return
        if (m.str("kind") != "text") throw EngineException("Only text messages can be retried")
        val conv = db.one("SELECT * FROM conversations WHERE id=?", m.str("conv_id"))!!
        db.exec("UPDATE messages SET status='pending' WHERE id=?", msgId)
        if (conv.str("kind") == "group") {
            val g = groupState(conv.str("group_id"))!!
            requireActiveMember(g)
            db.exec("DELETE FROM message_receipts WHERE msg_id=?", msgId)
            sendGroupContent(g, Content.GroupText(g.groupId, g.epoch, msgId, m.long("sent_at"), m.str("body")), msgId)
        } else {
            sendContent(conv.str("peer_id"), Content.Text(msgId, m.long("sent_at"), m.str("body")), ackId = msgId)
        }
        changed("messages", m.str("conv_id"))
    }

    fun deleteMessage(msgId: String) {
        val m = db.one("SELECT conv_id FROM messages WHERE id=?", msgId) ?: return
        db.tx {
            db.exec("DELETE FROM messages WHERE id=?", msgId)
            db.exec("DELETE FROM message_receipts WHERE msg_id=?", msgId)
            db.exec("DELETE FROM outbox WHERE msg_id=?", msgId)
        }
        db.one("SELECT id FROM attachments WHERE id=?", msgId)?.let { blobs.delete(msgId); db.exec("DELETE FROM attachments WHERE id=?", msgId) }
        changed("messages", m.str("conv_id"))
    }

    fun clearConversation(convId: String) {
        db.query("SELECT id FROM messages WHERE conv_id=?", convId).forEach { deleteMessage(it.str("id")) }
        db.exec("UPDATE conversations SET last_preview='', unread=0 WHERE id=?", convId)
        changed("conversations", convId)
    }

    fun markRead(convId: String) {
        val conv = db.one("SELECT * FROM conversations WHERE id=?", convId) ?: return
        val unread = db.query("SELECT id, sender_id FROM messages WHERE conv_id=? AND outgoing=0 AND status='received'", convId)
        db.exec("UPDATE messages SET status='read' WHERE conv_id=? AND outgoing=0 AND status='received'", convId)
        db.exec("UPDATE conversations SET unread=0 WHERE id=?", convId)
        if (setting("read_receipts", "1") == "1") {
            unread.groupBy { it.str("sender_id") }.forEach { (sender, rows) ->
                if (conv.str("kind") == "direct" || isKnownContact(sender)) runCatching {
                    rows.map { it.str("id") }.chunked(Content.MAX_RECEIPT_IDS).forEach { ids ->
                        sendContent(sender, Content.Receipt(Content.RECEIPT_READ, ids), ack = false)
                    }
                }
            }
        }
        changed("conversations", convId)
    }

    fun directConversation(peerId: String): String {
        val id = "d:$peerId"
        if (db.one("SELECT id FROM conversations WHERE id=?", id) == null) {
            val name = db.one("SELECT name FROM contacts WHERE id=?", peerId)?.str("name") ?: throw EngineException("Unknown contact")
            db.exec("INSERT INTO conversations (id, kind, peer_id, title) VALUES (?, 'direct', ?, ?)", id, peerId, name)
        }
        return id
    }

    // ================================================================== attachments

    fun sendFile(convId: String, name: String, mime: String, data: ByteArray): String {
        if (data.isEmpty()) throw EngineException("Empty file")
        if (data.size > Content.MAX_ATTACHMENT) throw EngineException("File too large (max ${Content.MAX_ATTACHMENT / 1024 / 1024} MB over Bluetooth)")
        val conv = db.one("SELECT * FROM conversations WHERE id=?", convId) ?: throw EngineException("No conversation")
        val msgId = UUID.randomUUID().toString()
        val now = clock()
        val chunks = data.toList().chunked(Content.ATTACHMENT_CHUNK).map { it.toByteArray() }
        val sha = Crypto.sha256(data)
        chunks.forEachIndexed { i, c -> blobs.writeChunk(msgId, i, c) }
        blobs.assemble(msgId, chunks.size)
        db.exec("INSERT INTO attachments (id, name, mime, size, sha256, chunk_count, received_chunks, complete) VALUES (?,?,?,?,?,?,?,1)",
            msgId, name.take(200), mime.take(100), data.size.toLong(), sha, chunks.size, chunks.size)
        insertMessage(msgId, convId, myId, true, "file", name.take(200), "pending", now)
        if (conv.str("kind") == "group") {
            val g = groupState(conv.str("group_id"))!!
            requireActiveMember(g)
            sendGroupContent(g, Content.Attachment(g.groupId, g.epoch, msgId, now, name, mime, data.size.toLong(), sha, chunks.size), msgId)
            chunks.forEachIndexed { i, c -> sendGroupContent(g, Content.AttachmentChunk(g.groupId, g.epoch, msgId, i, c), "$msgId#$i", trackReceipt = false) }
        } else {
            val peer = conv.str("peer_id")
            sendContent(peer, Content.Attachment("", 0, msgId, now, name, mime, data.size.toLong(), sha, chunks.size), ackId = msgId)
            chunks.forEachIndexed { i, c -> sendContent(peer, Content.AttachmentChunk("", 0, msgId, i, c), ackId = "$msgId#$i") }
        }
        refreshStatus(msgId)
        return msgId
    }

    fun readAttachment(msgId: String): ByteArray? =
        if (db.one("SELECT complete FROM attachments WHERE id=?", msgId)?.bool("complete") == true) blobs.read(msgId) else null

    // ================================================================== receiving

    private fun onPacket(from: LinkState, bytes: ByteArray) {
        val packet = try { Packet.decode(bytes) } catch (e: MalformedException) { return }
        val now = clock()
        if (packet.expiresAt < now || packet.expiresAt > now + MAX_FUTURE_MS) return
        val pid = packet.idHex()

        val seen = db.one("SELECT ack_dest, ack_msg_id FROM seen_packets WHERE packet_id=?", pid)
        if (seen != null) {
            // Duplicate of something we already delivered: re-acknowledge so the sender stops retrying.
            val dest = seen.strOrNull("ack_dest"); val mid = seen.strOrNull("ack_msg_id")
            if (dest != null && mid != null) runCatching { sendContent(dest, Content.Receipt(Content.RECEIPT_DELIVERED, listOf(mid)), ack = false) }
            return
        }
        db.exec("INSERT INTO seen_packets (packet_id, seen_at, expires_at) VALUES (?,?,?)", pid, now, packet.expiresAt)

        if (Hex.enc(packet.routingTag) in Crypto.myRoutingTags(myIdentity, now)) {
            deliverToMe(packet)
        } else if (relayEnabled && packet.ttl > 0) {
            if (!relay.admit(db, from.link.id, packet, bytes)) return
            val fwd = packet.copy(ttl = packet.ttl - 1, hops = packet.hops + 1).encode()
            relay.updatePacket(db, pid, fwd)
            authedLinks().filter { it !== from }.forEach { sendFrame(it, LinkFrame.Data(fwd)); relay.markForwarded(db, pid) }
            changed("stats", null)
        }
    }

    private fun deliverToMe(packet: Packet) {
        val opened = try { Crypto.openEnvelope(store, packet.payload, packet.aad()) } catch (e: Exception) {
            securityEvent("undecryptable_packet", null, "Envelope failed authentication"); return
        }
        val senderId = Crypto.contactId(opened.sender)
        if (isBlocked(senderId)) return
        val senderAddr = SignalProtocolAddress(senderId, Crypto.DEVICE_ID)
        val plain = try {
            when (opened.signalType) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val m = PreKeySignalMessage(opened.ciphertext)
                    if (m.identityKey != opened.sender) throw EngineException("identity mismatch")
                    upsertContact(senderId, opened.sender)
                    SessionCipher(store, myAddr, senderAddr).decrypt(m).also { Crypto.replenishPreKeys(store) }
                }
                CiphertextMessage.WHISPER_TYPE -> SessionCipher(store, myAddr, senderAddr).decrypt(SignalMessage(opened.ciphertext))
                CiphertextMessage.SENDERKEY_TYPE -> try {
                    GroupCipher(store, senderAddr).decrypt(opened.ciphertext)
                } catch (e: org.signal.libsignal.protocol.NoSessionException) {
                    // Sender key may arrive after the message over a different mesh path; retry later.
                    if (pendingGroupPackets.size < 200) pendingGroupPackets += clock() to packet.encode()
                    db.exec("DELETE FROM seen_packets WHERE packet_id=?", packet.idHex())
                    return
                }
                else -> throw EngineException("unknown ciphertext type")
            }
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            return
        } catch (e: Exception) {
            securityEvent("decrypt_failed", senderId, e.javaClass.simpleName); return
        }
        val content = try { Content.decode(plain) } catch (e: MalformedException) {
            securityEvent("malformed_content", senderId, e.message ?: ""); return
        }
        val fromGroupCipher = opened.signalType == CiphertextMessage.SENDERKEY_TYPE
        val ackId = handleContent(senderId, content, fromGroupCipher) ?: return
        if (ackId.isNotEmpty()) {
            db.exec("UPDATE seen_packets SET ack_dest=?, ack_msg_id=? WHERE packet_id=?", senderId, ackId, packet.idHex())
            runCatching { sendContent(senderId, Content.Receipt(Content.RECEIPT_DELIVERED, listOf(ackId)), ack = false) }
        }
    }

    /** Returns the id to acknowledge ("" = no ack, null = rejected). */
    private fun handleContent(senderId: String, c: Content, viaGroupCipher: Boolean): String? {
        val now = clock()
        // Group payloads must arrive via the group cipher; pairwise-only payloads must not.
        val groupPayload = c is Content.GroupText || (c is Content.Attachment && c.groupId.isNotEmpty()) || (c is Content.AttachmentChunk && c.groupId.isNotEmpty())
        if (groupPayload != viaGroupCipher) { securityEvent("wrong_channel", senderId, c.javaClass.simpleName); return null }
        return when (c) {
            is Content.Text -> {
                val conv = directConversation(senderId)
                if (insertMessage(c.msgId, conv, senderId, false, "text", c.text, "received", c.sentAt, unread = true)) changed("messages", conv)
                c.msgId
            }
            is Content.Profile -> {
                val n = c.name.trim().take(32)
                if (n.isNotEmpty()) {
                    db.exec("UPDATE contacts SET name=?, last_seen=? WHERE id=?", n, now, senderId)
                    db.exec("UPDATE conversations SET title=? WHERE id=?", n, "d:$senderId")
                    checkNameCollision(senderId, n)
                    changed("contacts", senderId)
                }
                // Session responder: reply with our profile once so both sides have names.
                if (myId > senderId && db.one("SELECT v FROM settings WHERE k=?", "profile_sent:$senderId") == null) {
                    putSetting("profile_sent:$senderId", "1")
                    runCatching { sendProfile(senderId) }
                }
                ""
            }
            is Content.Avatar -> {
                db.exec("UPDATE contacts SET avatar=? WHERE id=?", if (c.image.isEmpty()) null else c.image, senderId)
                changed("contacts", senderId); changed("conversations", "d:$senderId")
                ""
            }
            is Content.Receipt -> { onReceipt(senderId, c); "" }
            is Content.QrClaim -> { onQrClaim(senderId, c.token); "" }
            is Content.GroupOp -> { onGroupOp(senderId, c.signedOp); "" }
            is Content.SenderKey -> { onSenderKey(senderId, c); "" }
            is Content.GroupText -> {
                val g = groupState(c.groupId) ?: return null
                if (!acceptGroupMessage(g, senderId, c.epoch)) return null
                if (insertMessage(c.msgId, "g:${g.groupId}", senderId, false, "text", c.text, "received", c.sentAt, unread = true)) changed("messages", "g:${g.groupId}")
                c.msgId
            }
            is Content.Attachment -> {
                val conv = if (c.groupId.isEmpty()) directConversation(senderId) else {
                    val g = groupState(c.groupId) ?: return null
                    if (!acceptGroupMessage(g, senderId, c.epoch)) return null
                    "g:${g.groupId}"
                }
                if (db.one("SELECT id FROM attachments WHERE id=?", c.msgId) == null) {
                    db.exec("INSERT INTO attachments (id, name, mime, size, sha256, chunk_count) VALUES (?,?,?,?,?,?)",
                        c.msgId, c.name.take(200), c.mime.take(100), c.size, c.sha256, c.chunkCount)
                    insertMessage(c.msgId, conv, senderId, false, "file", c.name.take(200), "receiving", c.sentAt, unread = true)
                    tryCompleteAttachment(c.msgId)
                    changed("messages", conv)
                }
                c.msgId
            }
            is Content.AttachmentChunk -> {
                if (c.groupId.isNotEmpty()) {
                    val g = groupState(c.groupId) ?: return null
                    if (!acceptGroupMessage(g, senderId, c.epoch)) return null
                }
                val owner = db.one("SELECT sender_id FROM messages WHERE id=?", c.msgId)
                if (owner != null && owner.str("sender_id") != senderId) return null
                if (db.one("SELECT 1 AS x FROM attachment_chunks WHERE att_id=? AND idx=?", c.msgId, c.index) == null) {
                    blobs.writeChunk(c.msgId, c.index, c.data)
                    db.exec("INSERT INTO attachment_chunks (att_id, idx) VALUES (?,?)", c.msgId, c.index)
                    tryCompleteAttachment(c.msgId)
                }
                "${c.msgId}#${c.index}"
            }
        }
    }

    private fun tryCompleteAttachment(attId: String) {
        val a = db.one("SELECT * FROM attachments WHERE id=?", attId) ?: return
        if (a.bool("complete")) return
        val have = db.one("SELECT COUNT(*) AS n FROM attachment_chunks WHERE att_id=? AND idx < ?", attId, a.int("chunk_count"))!!.int("n")
        db.exec("UPDATE attachments SET received_chunks=? WHERE id=?", have, attId)
        if (have < a.int("chunk_count")) return
        blobs.assemble(attId, a.int("chunk_count"))
        val data = blobs.read(attId)
        if (data == null || data.size.toLong() != a.long("size") || !Crypto.sha256(data).contentEquals(a.blob("sha256"))) {
            securityEvent("attachment_integrity", null, "Attachment hash mismatch; discarded")
            blobs.delete(attId)
            db.exec("UPDATE messages SET status='failed' WHERE id=?", attId)
            return
        }
        db.exec("UPDATE attachments SET complete=1 WHERE id=?", attId)
        db.exec("UPDATE messages SET status='received' WHERE id=? AND status='receiving'", attId)
        db.one("SELECT conv_id FROM messages WHERE id=?", attId)?.let { changed("messages", it.str("conv_id")) }
    }

    private fun onReceipt(senderId: String, r: Content.Receipt) {
        r.msgIds.forEach { id ->
            if (r.kind == Content.RECEIPT_DELIVERED) db.exec("DELETE FROM outbox WHERE dest_id=? AND msg_id=?", senderId, id)
            val baseId = id.substringBefore('#')
            val m = db.one("SELECT conv_id, outgoing FROM messages WHERE id=?", baseId) ?: return@forEach
            if (!m.bool("outgoing")) return@forEach
            if (id.contains('#')) { refreshStatus(baseId); return@forEach }
            val status = if (r.kind == Content.RECEIPT_READ) "read" else "delivered"
            val prev = db.one("SELECT status FROM message_receipts WHERE msg_id=? AND member_id=?", baseId, senderId)?.str("status")
            if (prev != "read") db.exec("INSERT OR REPLACE INTO message_receipts (msg_id, member_id, status, at) VALUES (?,?,?,?)", baseId, senderId, status, clock())
            refreshStatus(baseId)
        }
    }

    /**
     * Message status is derived only from real receipts:
     * pending (nothing transmitted) → sent (left this device) → delivered/read (receipt from every recipient).
     */
    private fun refreshStatus(msgId: String) {
        val m = db.one("SELECT * FROM messages WHERE id=? AND outgoing=1", msgId) ?: return
        if (m.str("status") == "failed") return
        val conv = db.one("SELECT * FROM conversations WHERE id=?", m.str("conv_id")) ?: return
        val recipients: Set<String> = if (conv.str("kind") == "group") groupState(conv.str("group_id"))?.memberIds()?.minus(myId) ?: emptySet() else setOf(conv.str("peer_id"))
        val receipts = db.query("SELECT member_id, status FROM message_receipts WHERE msg_id=?", msgId).associate { it.str("member_id") to it.str("status") }
        val outstanding = db.one("SELECT COUNT(*) AS n FROM outbox WHERE msg_id LIKE ?", "$msgId%")!!.int("n")
        val attempted = db.one("SELECT COUNT(*) AS n FROM outbox WHERE msg_id LIKE ? AND attempts > 0", "$msgId%")!!.int("n")
        val status = when {
            recipients.isNotEmpty() && recipients.all { receipts[it] == "read" } && outstanding == 0 -> "read"
            recipients.isNotEmpty() && recipients.all { it in receipts } && outstanding == 0 -> "delivered"
            attempted > 0 || (outstanding == 0 && receipts.isNotEmpty()) -> "sent"
            else -> "pending"
        }
        if (status != m.str("status")) {
            db.exec("UPDATE messages SET status=? WHERE id=?", status, msgId)
            changed("messages", m.str("conv_id"))
        }
    }

    // ================================================================== groups

    fun groupState(groupId: String): GroupState? = db.one("SELECT state FROM groups WHERE id=?", groupId)?.let { GroupState.decode(it.blob("state")) }

    private fun requireActiveMember(g: GroupState) {
        if (!g.isMember(myId)) throw EngineException("You are no longer a member of this group")
    }

    private fun acceptGroupMessage(g: GroupState, senderId: String, epoch: Int): Boolean {
        if (epoch != g.epoch || !g.isMember(senderId) || !g.isMember(myId)) {
            securityEvent("group_message_rejected", senderId, "Not a member at epoch $epoch (current ${g.epoch})")
            return false
        }
        return true
    }

    private fun memberFromContact(id: String): GroupState.Member {
        if (id == myId) return GroupState.Member(myId, myIdentity.serialize(), myName)
        val c = db.one("SELECT identity_key, name FROM contacts WHERE id=?", id) ?: throw EngineException("Unknown contact")
        return GroupState.Member(id, c.blob("identity_key"), c.str("name"))
    }

    fun createGroup(name: String, memberIds: List<String>): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > 48) throw EngineException("Group name must be 1–48 characters")
        val members = (listOf(myId) + memberIds.distinct().filter { it != myId }).map { memberFromContact(it) }
        if (members.size > GroupState.MAX_MEMBERS) throw EngineException("Too many members")
        val gid = UUID.randomUUID().toString()
        val state = GroupState(gid, 1, n, members, setOf(myId), GroupState.OP_CREATE, myId, clock()).signedBy(store.identityKeyPair)
        applyGroupState(state, fromInvite = false)
        return "g:$gid"
    }

    private fun mutateGroup(groupId: String, op: Int, f: (GroupState) -> GroupState) {
        val g = groupState(groupId) ?: throw EngineException("No such group")
        if (op != GroupState.OP_LEAVE && !g.isAdmin(myId)) throw EngineException("Only admins can do this")
        val next = f(g).copy(epoch = g.epoch + 1, opType = op, issuerId = myId, issuedAt = clock()).signedBy(store.identityKeyPair)
        if (!GroupState.isValidTransition(g, next)) throw EngineException("Invalid group change")
        val previousMembers = g.memberIds()
        applyGroupState(next, fromInvite = false, notifyAlso = previousMembers)
    }

    fun addMembers(groupId: String, ids: List<String>) = mutateGroup(groupId, GroupState.OP_ADD) { g ->
        g.copy(members = g.members + ids.filter { !g.isMember(it) }.distinct().map { memberFromContact(it) })
    }

    fun removeMember(groupId: String, id: String) = mutateGroup(groupId, GroupState.OP_REMOVE) { g ->
        if (id == myId) throw EngineException("Use leave group instead")
        g.copy(members = g.members.filter { it.id != id }, admins = g.admins - id)
    }

    fun setAdmin(groupId: String, id: String, admin: Boolean) = mutateGroup(groupId, GroupState.OP_ADMIN) { g ->
        if (!g.isMember(id)) throw EngineException("Not a member")
        val admins = if (admin) g.admins + id else g.admins - id
        if (admins.isEmpty()) throw EngineException("A group needs at least one admin")
        g.copy(admins = admins)
    }

    fun renameGroup(groupId: String, name: String) = mutateGroup(groupId, GroupState.OP_RENAME) { g ->
        val n = name.trim(); if (n.isEmpty() || n.length > 48) throw EngineException("Invalid name"); g.copy(name = n)
    }

    fun leaveGroup(groupId: String) {
        val g = groupState(groupId) ?: return
        if (g.isAdmin(myId) && g.admins.size == 1 && g.members.size > 1) throw EngineException("Make another member admin before leaving")
        mutateGroup(groupId, GroupState.OP_LEAVE) { it.copy(members = it.members.filter { m -> m.id != myId }, admins = it.admins - myId) }
    }

    fun acceptInvite(groupId: String) {
        val inv = db.one("SELECT op FROM group_invites WHERE group_id=?", groupId) ?: throw EngineException("No invite")
        val state = GroupState.decode(inv.blob("op"))
        db.exec("DELETE FROM group_invites WHERE group_id=?", groupId)
        if (!state.verify() || !state.isMember(myId)) throw EngineException("Invite is no longer valid")
        applyGroupState(state, fromInvite = true)
    }

    fun rejectInvite(groupId: String) {
        db.exec("DELETE FROM group_invites WHERE group_id=?", groupId)
        db.exec("DELETE FROM pending_sender_keys WHERE group_id=?", groupId)
        changed("groups", groupId)
    }

    /** Persist a validated state, rotate our sender key for the new epoch and distribute it. */
    private fun applyGroupState(state: GroupState, fromInvite: Boolean, notifyAlso: Set<String> = emptySet()) {
        val gid = state.groupId
        val stillMember = state.isMember(myId)
        db.tx {
            db.exec("INSERT OR REPLACE INTO groups (id, name, epoch, state, active, created_at) VALUES (?,?,?,?,?, COALESCE((SELECT created_at FROM groups WHERE id=?), ?))",
                gid, state.name, state.epoch, state.encode(), stillMember, gid, clock())
            db.exec("DELETE FROM group_members WHERE group_id=?", gid)
            state.members.forEach { db.exec("INSERT INTO group_members (group_id, member_id, is_admin) VALUES (?,?,?)", gid, it.id, it.id in state.admins) }
            db.exec("INSERT OR IGNORE INTO group_epochs (group_id, epoch, op, applied_at) VALUES (?,?,?,?)", gid, state.epoch, state.encode(), clock())
            if (db.one("SELECT id FROM conversations WHERE id=?", "g:$gid") == null)
                db.exec("INSERT INTO conversations (id, kind, group_id, title) VALUES (?, 'group', ?, ?)", "g:$gid", gid, state.name)
            else db.exec("UPDATE conversations SET title=? WHERE id=?", state.name, "g:$gid")
        }
        // Make sure every member is a known contact so we can address them (keys come from the signed state).
        state.members.filter { it.id != myId }.forEach { upsertContact(it.id, IdentityKey(it.identityKey), it.name, touchSeen = false) }

        val opBytes = state.encode()
        if (state.issuerId == myId) {
            // Distribute the signed state to everyone affected (new members + previous members, e.g. the removed one).
            (state.memberIds() + notifyAlso - myId).forEach { runCatching { sendContent(it, Content.GroupOp(opBytes)) } }
        }
        if (stillMember) {
            val skdm = GroupSessionBuilder(store).create(myAddr, state.distributionId())
            (state.memberIds() - myId).forEach { runCatching { sendContent(it, Content.SenderKey(gid, state.epoch, skdm.serialize())) } }
        }
        db.query("SELECT sender_id, epoch, dist FROM pending_sender_keys WHERE group_id=? AND epoch<=?", gid, state.epoch).forEach {
            db.exec("DELETE FROM pending_sender_keys WHERE group_id=? AND sender_id=?", gid, it.str("sender_id"))
            onSenderKey(it.str("sender_id"), Content.SenderKey(gid, it.int("epoch"), it.blob("dist")))
        }
        insertSystemMessage("g:$gid", describeOp(state, fromInvite))
        changed("groups", gid); changed("conversations", "g:$gid")
    }

    private fun describeOp(s: GroupState, fromInvite: Boolean): String {
        val who = if (s.issuerId == myId) "You" else s.member(s.issuerId)?.name ?: db.one("SELECT name FROM contacts WHERE id=?", s.issuerId)?.str("name") ?: "Someone"
        return when {
            fromInvite -> "You joined \"${s.name}\""
            s.opType == GroupState.OP_CREATE -> "$who created \"${s.name}\""
            s.opType == GroupState.OP_ADD -> "$who added members"
            s.opType == GroupState.OP_REMOVE -> if (s.isMember(myId)) "$who removed a member · keys rotated" else "$who removed you"
            s.opType == GroupState.OP_LEAVE -> "$who left · keys rotated"
            s.opType == GroupState.OP_ADMIN -> "$who changed admins"
            s.opType == GroupState.OP_RENAME -> "$who renamed the group to \"${s.name}\""
            else -> "Group updated"
        }
    }

    private fun onGroupOp(senderId: String, bytes: ByteArray) {
        val next = try { GroupState.decode(bytes) } catch (e: MalformedException) { return }
        val current = groupState(next.groupId)
        if (current == null) {
            // Unknown group: an invitation. Must be self-consistent, signed, issued by its admin, and include us.
            if (next.verify() && next.isAdmin(next.issuerId) && next.isMember(myId) && next.issuerId == senderId) {
                db.exec("INSERT OR REPLACE INTO group_invites (group_id, from_id, op, received_at) VALUES (?,?,?,?)", next.groupId, senderId, bytes, clock())
                changed("groups", next.groupId)
            } else securityEvent("group_invite_rejected", senderId, "Invalid group invitation")
            return
        }
        if (next.epoch <= current.epoch) return
        if (!GroupState.isValidTransition(current, next)) {
            securityEvent("group_op_rejected", senderId, "Unauthorized membership change rejected")
            return
        }
        applyGroupState(next, fromInvite = false)
    }

    private fun onSenderKey(senderId: String, c: Content.SenderKey) {
        val g = groupState(c.groupId)
        if (g == null || c.epoch > g.epoch) {
            // Arrived before we accepted the invite / applied the new epoch: keep it (bounded) for later.
            if (db.one("SELECT COUNT(*) AS n FROM pending_sender_keys")!!.int("n") < 500)
                db.exec("INSERT OR REPLACE INTO pending_sender_keys (group_id, sender_id, epoch, dist, received_at) VALUES (?,?,?,?,?)",
                    c.groupId, senderId, c.epoch, c.distribution, clock())
            return
        }
        if (c.epoch != g.epoch || !g.isMember(senderId) || !g.isMember(myId)) return
        val skdm = try { SenderKeyDistributionMessage(c.distribution) } catch (e: Exception) { return }
        if (skdm.distributionId != g.distributionId()) return
        GroupSessionBuilder(store).process(SignalProtocolAddress(senderId, Crypto.DEVICE_ID), skdm)
        // Retry group messages that were waiting for this key.
        val retry = pendingGroupPackets.toList(); pendingGroupPackets.clear()
        retry.forEach { (_, p) -> runCatching { deliverToMe(Packet.decode(p)) } }
    }

    /** Encrypt once with our sender key, seal individually to each member. */
    private fun sendGroupContent(g: GroupState, c: Content, ackBase: String, trackReceipt: Boolean = true) {
        val ct = GroupCipher(store, myAddr).encrypt(g.distributionId(), Content.encode(c))
        (g.memberIds() - myId).forEach { member ->
            runCatching { queuePacket(member, CiphertextMessage.SENDERKEY_TYPE, ct.serialize(), true, ackBase) }
        }
        if (trackReceipt) refreshStatus(ackBase)
    }

    // ================================================================== QR verification

    fun createQr(ephemeralId: ByteArray): String {
        val now = clock()
        issuedQr.entries.removeIf { it.value < now }
        val token = Crypto.randomBytes(16)
        issuedQr[Hex.enc(token)] = now + Crypto.QR_TTL_MS
        return Crypto.encodeQr(Crypto.QrPayload(token, Hex.dec(myId), ephemeralId, now + Crypto.QR_TTL_MS))
    }

    /**
     * Handle a scanned QR. Returns the BLE ephemeral id (hex) to connect to, or null when the
     * contact was already connected and is now verified.
     */
    fun scanQr(text: String): String? {
        val p = Crypto.decodeQr(text)
        if (p.expiresAt < clock()) throw EngineException("This QR code has expired. Ask your contact to refresh it.")
        val peerId = Hex.enc(p.fingerprint)
        if (peerId == myId) throw EngineException("That is your own QR code")
        val token = Hex.enc(p.token)
        if (linkFor(peerId) != null) { completeQrVerification(peerId, token); return null }
        pendingQrVerify[peerId] = token
        return Hex.enc(p.ephemeralId)
    }

    /** Called by the transport when a QR-initiated connection reached a different identity. */
    fun qrPeerMismatch() {
        securityEvent("qr_mismatch", null, "Device at the scanned code presented a different identity")
        changed("security", null)
    }

    private fun completeQrVerification(peerId: String, token: String) {
        setVerified(peerId, true, viaQr = true)
        runCatching { sendContent(peerId, Content.QrClaim(Hex.dec(token)), ack = false) }
    }

    private fun onQrClaim(senderId: String, token: ByteArray) {
        val t = Hex.enc(token)
        val exp = issuedQr[t]
        val used = db.one("SELECT token FROM used_qr_tokens WHERE token=?", t) != null
        if (exp == null || exp < clock() || used) {
            securityEvent("qr_replay_rejected", senderId, "Expired, unknown or reused QR token"); return
        }
        issuedQr.remove(t)
        db.exec("INSERT INTO used_qr_tokens (token, used_at) VALUES (?,?)", t, clock())
        setVerified(senderId, true, viaQr = true)
    }

    // ================================================================== contacts & security

    fun setVerified(peerId: String, verified: Boolean, viaQr: Boolean = false) {
        db.exec("UPDATE contacts SET verified=?, verified_at=?, identity_changed=0 WHERE id=?", verified, if (verified) clock() else null, peerId)
        securityEvent(if (verified) "contact_verified" else "contact_unverified", peerId, if (viaQr) "QR code" else "Safety number")
        changed("contacts", peerId); changed("security", null)
    }

    fun block(peerId: String, blocked: Boolean) {
        db.exec("UPDATE contacts SET blocked=? WHERE id=?", blocked, peerId)
        if (blocked) {
            db.exec("DELETE FROM outbox WHERE dest_id=?", peerId)
            linkFor(peerId)?.let { drop(it) }
        }
        securityEvent(if (blocked) "contact_blocked" else "contact_unblocked", peerId, "")
        changed("contacts", peerId)
    }

    /** "Revoke device": forget sessions and trust for this identity; a new connection starts from scratch. */
    fun revoke(peerId: String) {
        store.deleteAllSessions(peerId)
        store.deleteSession(SignalProtocolAddress(peerId, Crypto.DEVICE_ID))
        db.exec("UPDATE contacts SET verified=0, verified_at=NULL WHERE id=?", peerId)
        db.exec("DELETE FROM outbox WHERE dest_id=?", peerId)
        db.exec("DELETE FROM settings WHERE k=?", "profile_sent:$peerId")
        db.exec("DELETE FROM settings WHERE k=?", "avatar_sent:$peerId")
        linkFor(peerId)?.let { drop(it) }
        securityEvent("device_revoked", peerId, "Sessions deleted")
        changed("contacts", peerId)
    }

    fun safetyNumber(peerId: String): String = Crypto.safetyNumber(myIdentity, peerIdentity(peerId))

    private fun isBlocked(id: String) = db.one("SELECT blocked FROM contacts WHERE id=?", id)?.bool("blocked") == true
    private fun isKnownContact(id: String) = db.one("SELECT id FROM contacts WHERE id=?", id) != null

    /** Returns true if this is a brand-new contact. */
    private fun upsertContact(id: String, identity: IdentityKey, name: String? = null, touchSeen: Boolean = true): Boolean {
        val now = clock()
        val existing = db.one("SELECT id, name FROM contacts WHERE id=?", id)
        if (existing == null) {
            db.exec("INSERT INTO contacts (id, identity_key, name, first_seen, last_seen) VALUES (?,?,?,?,?)", id, identity.serialize(), name ?: "", now, now)
            store.saveIdentity(SignalProtocolAddress(id, Crypto.DEVICE_ID), identity)
            name?.let { checkNameCollision(id, it) }
            return true
        }
        if (touchSeen) db.exec("UPDATE contacts SET last_seen=? WHERE id=?", now, id)
        if (name != null && existing.str("name").isEmpty()) db.exec("UPDATE contacts SET name=? WHERE id=?", name, id)
        return false
    }

    /**
     * Identity is the key, not the name. A *different* key using the same name as an existing contact
     * may be that person on a new phone — or an impersonator. Flag it so the UI warns and asks to verify.
     */
    private fun checkNameCollision(id: String, name: String) {
        val clash = db.query("SELECT id FROM contacts WHERE id<>? AND name=? COLLATE NOCASE", id, name)
        if (clash.isNotEmpty()) {
            db.exec("UPDATE contacts SET identity_changed=1 WHERE id=?", id)
            securityEvent("identity_warning", id, "\"$name\" connected with a different security key than a saved contact. Verify before trusting.")
        }
    }

    // ================================================================== periodic work

    /** Call every ~10 s: retries, expiry, relay queue maintenance, handshake timeouts. */
    fun tick() {
        val now = clock()
        // Each step runs on its own: one failure must never stop retries and status updates.
        step("reap") { reapStaleLinks() }
        step("expire") { expireOutbox(now) }
        // Retry with exponential backoff.
        step("retry") {
            db.query("SELECT packet_id, attempts, last_attempt FROM outbox").forEach { r ->
                val backoff = minOf(RETRY_BASE_MS shl minOf(r.int("attempts"), 5), RETRY_MAX_MS)
                if (now - r.long("last_attempt") >= backoff) runCatching { transmit(r.str("packet_id")) }
                    .onFailure { log("retry ${r.str("packet_id").take(8)} failed: ${it.javaClass.simpleName}") }
            }
        }
        step("status") {
            db.query("SELECT DISTINCT substr(msg_id, 1, 36) AS m FROM outbox WHERE msg_id IS NOT NULL")
                .forEach { runCatching { refreshStatus(it.str("m")) } }
        }
        step("seen") { db.exec("DELETE FROM seen_packets WHERE expires_at < ?", now) }
        step("relay") { relay.expire(db, now) }
        step("groups") { pendingGroupPackets.removeIf { now - it.first > 10 * 60_000 } }
        changed("stats", null)
    }

    private inline fun step(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("tick step $name failed: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Expire undelivered packets -> messages failed. We never claim delivery without a receipt. */
    private fun expireOutbox(now: Long) {
        val expired = db.query("SELECT packet_id, msg_id FROM outbox WHERE expires_at < ?", now)
        expired.forEach { r ->
            db.exec("DELETE FROM outbox WHERE packet_id=?", r.str("packet_id"))
            r.strOrNull("msg_id")?.substringBefore('#')?.let { mid ->
                db.one("SELECT conv_id FROM messages WHERE id=? AND outgoing=1 AND status IN ('pending','sent')", mid)?.let {
                    db.exec("UPDATE messages SET status='failed' WHERE id=?", mid); changed("messages", it.str("conv_id"))
                }
            }
        }
    }

    fun stats(): Map<String, Any?> = mapOf(
        "connected" to authedLinks().size,
        "contacts" to db.one("SELECT COUNT(*) AS n FROM contacts WHERE blocked=0")!!.int("n"),
        "verified" to db.one("SELECT COUNT(*) AS n FROM contacts WHERE verified=1")!!.int("n"),
        "pending" to db.one("SELECT COUNT(DISTINCT substr(msg_id,1,36)) AS n FROM outbox WHERE needs_ack=1")!!.int("n"),
        "groups" to db.one("SELECT COUNT(*) AS n FROM groups WHERE active=1")!!.int("n"),
        "invites" to db.one("SELECT COUNT(*) AS n FROM group_invites")!!.int("n"),
        "relayQueue" to db.one("SELECT COUNT(*) AS n FROM relay_queue")!!.int("n"),
        "relayed" to relay.forwardedTotal,
        "relayEnabled" to relayEnabled,
    )

    // ================================================================== helpers

    private fun insertMessage(id: String, convId: String, sender: String, outgoing: Boolean, kind: String, body: String,
                              status: String, sentAt: Long, unread: Boolean = false): Boolean {
        if (db.one("SELECT id FROM messages WHERE id=?", id) != null) return false
        val now = clock()
        db.exec("INSERT INTO messages (id, conv_id, sender_id, outgoing, kind, body, status, sent_at, received_at) VALUES (?,?,?,?,?,?,?,?,?)",
            id, convId, sender, outgoing, kind, body, status, sentAt, now)
        val preview = if (kind == "file") "📎 $body" else body
        db.exec("UPDATE conversations SET last_msg_at=?, last_preview=?, unread=unread+? WHERE id=?", now, preview.take(80), if (unread) 1 else 0, convId)
        changed("conversations", convId)
        return true
    }

    private fun insertSystemMessage(convId: String, text: String) =
        insertMessage(UUID.randomUUID().toString(), convId, "system", false, "system", text, "read", clock())

    fun setting(k: String, def: String): String = db.one("SELECT v FROM settings WHERE k=?", k)?.str("v") ?: def
    fun putSetting(k: String, v: String) = db.exec("INSERT OR REPLACE INTO settings (k, v) VALUES (?,?)", k, v)

    private fun securityEvent(kind: String, contactId: String?, detail: String) {
        log("event $kind peer=${short(contactId)}")
        db.exec("INSERT INTO security_events (at, kind, contact_id, detail) VALUES (?,?,?,?)", clock(), kind, contactId, detail)
        db.exec("DELETE FROM security_events WHERE id NOT IN (SELECT id FROM security_events ORDER BY id DESC LIMIT 500)")
        changed("security", contactId)
    }

    private fun changed(kind: String, id: String?) = runCatching { listener.onChange(kind, id) }

    companion object {
        const val MESSAGE_TTL_MS = 7L * 24 * 3600_000
        const val MAX_FUTURE_MS = 8L * 24 * 3600_000
        const val RETRY_BASE_MS = 15_000L
        const val RETRY_MAX_MS = 10 * 60_000L
    }
}

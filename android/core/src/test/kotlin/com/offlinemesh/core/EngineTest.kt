package com.offlinemesh.core

import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.db.one
import com.offlinemesh.core.engine.Engine
import com.offlinemesh.core.engine.EngineException
import com.offlinemesh.core.engine.GroupState
import com.offlinemesh.core.engine.contacts
import com.offlinemesh.core.engine.groupDetails
import com.offlinemesh.core.engine.invites
import com.offlinemesh.core.engine.messages
import com.offlinemesh.core.engine.securityEvents
import com.offlinemesh.core.proto.Chunker
import com.offlinemesh.core.proto.LinkFrame
import com.offlinemesh.core.proto.Packet
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private fun texts(e: Engine, conv: String) = e.messages(conv).filter { it["kind"] == "text" }.map { it["body"] }
    private fun status(e: Engine, conv: String, id: String) = e.messages(conv).first { it["id"] == id }["status"]
    private fun Engine.dm(other: Engine) = directConversation(other.myId)

    @Test fun twoPhonesDiscoverHandshakeAndChatBothWays() {
        val net = Net(); val a = net.node("Asha"); val b = net.node("Bilal")
        net.connect("Asha", "Bilal")
        assertEquals(listOf(b.myId), a.connectedPeerIds())
        // Names exchanged over the encrypted session, not in the clear.
        assertEquals("Bilal", a.contacts().single()["name"])
        assertEquals("Asha", b.contacts().single()["name"])

        val m1 = a.sendText(a.dm(b), "hello from A"); net.pump()
        assertEquals(listOf("hello from A"), texts(b, b.dm(a)))
        assertEquals("delivered", status(a, a.dm(b), m1))

        val m2 = b.sendText(b.dm(a), "hi A, this is B"); net.pump()
        assertEquals(listOf("hello from A", "hi A, this is B"), texts(a, a.dm(b)))
        assertEquals("delivered", status(b, b.dm(a), m2))

        b.markRead(b.dm(a)); net.pump()
        assertEquals("read", status(a, a.dm(b), m1))
    }

    @Test fun plaintextNeverOnTheWire() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        a.sendText(a.dm(b), "SECRET-PLAINTEXT-123"); net.pump()
        val wire = net.captured.joinToString("") { String(it.third, Charsets.ISO_8859_1) }
        assertFalse(wire.contains("SECRET-PLAINTEXT-123"))
        assertFalse(wire.contains("A\u0000") && wire.contains("profile")) // names travel only inside E2E content
    }

    @Test fun offlineMessageQueuedThenDeliveredOnReconnect() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B"); net.disconnect("A", "B")
        val id = a.sendText(a.dm(b), "while you were away")
        assertEquals("pending", status(a, a.dm(b), id))
        net.tickAll(60_000)
        assertEquals("pending", status(a, a.dm(b), id))  // no path -> never claims delivery
        net.connect("A", "B")
        assertEquals(listOf("while you were away"), texts(b, b.dm(a)))
        assertEquals("delivered", status(a, a.dm(b), id))
    }

    @Test fun messageExpiresWhenNoPath() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B"); net.disconnect("A", "B")
        val id = a.sendText(a.dm(b), "never arrives")
        net.tickAll(Engine.MESSAGE_TTL_MS + 1000)
        assertEquals("failed", status(a, a.dm(b), id))
        a.retryMessage(id)
        assertEquals("pending", status(a, a.dm(b), id))
    }

    @Test fun threePhoneRelayDeliversAndRelayCannotRead() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val c = net.node("C")
        net.connect("A", "C"); net.disconnect("A", "C")      // A and C met once (session exists)
        net.connect("A", "B"); net.connect("B", "C")          // now only reachable via B
        val id = a.sendText(a.dm(c), "via the middle phone"); net.pump()
        assertEquals(listOf("via the middle phone"), texts(c, c.dm(a)))
        assertEquals("delivered", status(a, a.dm(c), id))     // receipt also travelled back via B
        // Relay stored only ciphertext and has no message rows.
        assertEquals(0, b.db.one("SELECT COUNT(*) AS n FROM messages")!!.int("n"))
        val relayed = b.db.query("SELECT packet FROM relay_queue").map { Packet.decode(it.blob("packet")) }
        assertTrue(relayed.isNotEmpty())
        relayed.forEach { p -> assertThrows(Exception::class.java) { Crypto.openEnvelope(b.store, p.payload, p.aad()) } }
        assertTrue(relayed.all { it.hops >= 1 && it.ttl < Packet.DEFAULT_TTL })
    }

    @Test fun relayDisabledDoesNotForward() {
        val net = Net(); val a = net.node("A"); val b = net.node("B", relay = false); val c = net.node("C")
        net.connect("A", "C"); net.disconnect("A", "C")
        net.connect("A", "B"); net.connect("B", "C")
        val id = a.sendText(a.dm(c), "x"); net.pump()
        assertTrue(texts(c, c.dm(a)).isEmpty())
        assertEquals("pending", status(a, a.dm(c), id))
    }

    @Test fun storeAndForwardThroughMovingRelay() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val c = net.node("C")
        net.connect("A", "C"); net.disconnect("A", "C")
        net.connect("A", "B")
        a.sendText(a.dm(c), "carried by B"); net.pump()
        net.disconnect("A", "B")
        net.connect("B", "C")                                  // B walks over to C later
        assertEquals(listOf("carried by B"), texts(c, c.dm(a)))
    }

    @Test fun replayedPacketIsIgnored() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        net.captured.clear()
        a.sendText(a.dm(b), "once"); net.pump()
        val replay = net.captured.filter { it.first == "A" }.map { it.third }
        net.inject("B", "A", replay)
        net.tickAll(Engine.RETRY_MAX_MS)
        assertEquals(listOf("once"), texts(b, b.dm(a)))
    }

    @Test fun tamperedPacketRejected() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        val before = b.securityEvents().size
        // Build a valid link frame carrying a packet whose payload was modified in transit.
        net.tap = { from, _, chunk -> if (from == "A") null else chunk }
        a.sendText(a.dm(b), "do not modify"); net.pump()
        val pkt = Packet.decode(a.db.query("SELECT packet FROM outbox").single().blob("packet"))
        val bad = pkt.copy(payload = pkt.payload.copyOf().also { it[40] = (it[40] + 1).toByte() })
        net.tap = null
        net.inject("B", "A", Chunker.split(999, LinkFrame.encode(LinkFrame.Data(bad.encode())), 185))
        assertTrue(texts(b, b.dm(a)).isEmpty())
        assertTrue(b.securityEvents().size > before)
        // Malformed garbage is dropped without crashing.
        net.inject("B", "A", listOf(byteArrayOf(1, 2, 3), ByteArray(300) { 7 }))
    }

    @Test fun fakeIdentityHandshakeRejected() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val m = net.node("Mallory")
        // Mallory replays B's signed auth frame to A on a fresh link: nonce binding makes it fail.
        net.connect("A", "B")
        val bAuth = net.captured.filter { it.first == "B" && it.second == "A" }.map { it.third }
        net.connect("A", "Mallory")
        net.disconnect("A", "Mallory")
        val fresh = object : com.offlinemesh.core.engine.Link {
            override val id = "evil"; override val maxChunk = 185
            override fun send(chunk: ByteArray) {}; override fun close() {}
        }
        a.linkUp(fresh)
        bAuth.forEach { a.linkChunk("evil", it) }
        assertEquals(setOf(b.myId, m.myId).size, 2)
        assertFalse(a.connectedPeerIds().count { it == b.myId } > 1)
    }

    @Test fun groupLifecycleAndRemovedMemberCannotRead() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val c = net.node("C")
        net.connect("A", "B"); net.connect("A", "C"); net.connect("B", "C")
        val conv = a.createGroup("Trip", listOf(b.myId, c.myId)); net.pump()
        val gid = conv.removePrefix("g:")
        assertEquals(1, b.invites().size)
        b.acceptInvite(gid); c.acceptInvite(gid); net.pump()

        a.sendText(conv, "group hello"); net.pump()
        assertEquals(listOf("group hello"), texts(b, conv)); assertEquals(listOf("group hello"), texts(c, conv))
        c.sendText(conv, "from C"); net.pump()
        assertTrue(texts(a, conv).contains("from C") && texts(b, conv).contains("from C"))

        // Non-admin cannot remove.
        assertThrows(EngineException::class.java) { b.removeMember(gid, c.myId) }

        a.removeMember(gid, c.myId); net.pump()
        assertEquals(false, c.groupDetails(gid)!!["isMember"])
        assertEquals(2, b.groupState(gid)!!.members.size)
        net.captured.clear()
        a.sendText(conv, "after removal"); net.pump()
        assertTrue(texts(b, conv).contains("after removal"))
        assertFalse(texts(c, conv).contains("after removal"))
        assertThrows(EngineException::class.java) { c.sendText(conv, "still here?") }
    }

    @Test fun forgedGroupChangeRejected() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val c = net.node("C")
        net.connect("A", "B"); net.connect("A", "C"); net.connect("B", "C")
        val gid = a.createGroup("G", listOf(b.myId, c.myId)).removePrefix("g:"); net.pump()
        b.acceptInvite(gid); c.acceptInvite(gid); net.pump()
        // B (not admin) signs a state making itself admin and removing A, and sends it to C.
        val cur = b.groupState(gid)!!
        val forged = cur.copy(epoch = cur.epoch + 1, admins = setOf(b.myId), members = cur.members.filter { it.id != a.myId },
            opType = GroupState.OP_REMOVE, issuerId = b.myId).signedBy(b.store.identityKeyPair)
        assertFalse(GroupState.isValidTransition(cur, forged))
        assertEquals(cur.epoch, c.groupState(gid)!!.epoch)
    }

    @Test fun attachmentTransferIntegrity() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        val data = ByteArray(100_000) { (it * 31).toByte() }
        val id = a.sendFile(a.dm(b), "photo.jpg", "image/jpeg", data); net.pump()
        assertArrayEquals(data, b.readAttachment(id))
        assertEquals("delivered", status(a, a.dm(b), id))
    }

    @Test fun qrVerificationMutualAndTokenSingleUse() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        val qr = a.createQr(ByteArray(8) { 1 })
        assertNull(b.scanQr(qr)); net.pump()
        assertEquals(true, b.contacts().single()["verified"])
        assertEquals(true, a.contacts().single()["verified"])
        // Same token used again (e.g. a photo of the screen) is rejected.
        a.setVerified(b.myId, false)
        b.scanQr(qr); net.pump()
        assertEquals(false, a.contacts().single()["verified"])
        assertTrue(a.securityEvents().any { it["kind"] == "qr_replay_rejected" })
        // Expired codes are refused.
        val old = a.createQr(ByteArray(8)); net.now += Crypto.QR_TTL_MS + 1
        assertThrows(EngineException::class.java) { b.scanQr(old) }
    }

    @Test fun blockedContactCannotConnectOrMessage() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        a.block(b.myId, true); net.pump()
        net.disconnect("A", "B"); net.connect("A", "B")
        assertTrue(a.connectedPeerIds().isEmpty())
    }

    @Test fun sameNameDifferentKeyIsFlagged() {
        val net = Net(); val a = net.node("A"); val b1 = net.node("Bob"); val b2 = net.node("Bob2")
        net.connect("A", "Bob")
        b2.setMyName("Bob")
        net.connect("A", "Bob2")
        assertTrue(a.contacts().any { it["identityWarning"] == true })
        assertTrue(a.securityEvents().any { it["kind"] == "identity_warning" })
        assertNotEquals(b1.myId, b2.myId)
    }

    @Test fun safetyNumbersMatchOnBothSides() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        assertEquals(a.safetyNumber(b.myId), b.safetyNumber(a.myId))
    }

    @Test fun restartKeepsSessionsAndHistory() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        a.sendText(a.dm(b), "before restart"); net.pump()
        // Simulate B restarting: new Engine over the same database.
        val b2 = Engine(b.db, MemBlobs(), { _, _ -> }, { net.now })
        net.disconnect("A", "B")
        net.nodes["B"] = b2
        net.connect("A", "B")
        a.sendText(a.dm(b2), "after restart"); net.pump()
        assertEquals(listOf("before restart", "after restart"), texts(b2, b2.directConversation(a.myId)))
    }
}

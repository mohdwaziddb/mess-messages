package com.offlinemesh.core

import com.offlinemesh.core.engine.Engine
import com.offlinemesh.core.engine.Link
import com.offlinemesh.core.engine.messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models the real BLE topology, which the simple [Net] does not:
 *  - each side names a link after the *other* phone and its role ("c:<peer>" when it dialed, "p:<peer>" when it was dialed),
 *    so the two ends of one channel have different ids;
 *  - two phones that dial each other end up with two channels at once, which the engine must de-duplicate;
 *  - messages must then flow in BOTH directions and receipts must clear the sender's queue.
 */
class BleTopologyTest {
    private var now = 1_800_000_000_000L
    private val queue = ArrayDeque<Triple<Engine, String, ByteArray>>()

    private inner class End(override val id: String, override val initiator: Boolean) : Link {
        override val maxChunk = 182
        lateinit var to: Engine
        lateinit var peer: End
        var open = true
        override fun send(chunk: ByteArray) { if (open && peer.open) queue.addLast(Triple(to, peer.id, chunk)) }
        // Closing one logical link ends only that channel (the transport must not kill a shared connection).
        override fun close() { open = false; peer.open = false; owner.linkDown(id); peer.owner.linkDown(peer.id) }
        lateinit var owner: Engine
    }

    private fun node(name: String) = Engine(JdbcSql(), MemBlobs(), { _, _ -> }, { now }).also { it.createIdentity(name) }

    /** [dialer] connects to [dialed] as BLE central. */
    private fun channel(dialer: Engine, dialerName: String, dialed: Engine, dialedName: String): End {
        val c = End("c:$dialedName", true); val p = End("p:$dialerName", false)
        c.owner = dialer; c.to = dialed; c.peer = p
        p.owner = dialed; p.to = dialer; p.peer = c
        dialer.linkUp(c); dialed.linkUp(p)
        return c
    }

    private fun pump() { var n = 0; while (queue.isNotEmpty() && n++ < 200_000) { val (e, id, c) = queue.removeFirst(); e.linkChunk(id, c) } }

    private fun outbox(e: Engine) = e.db.query("SELECT COUNT(*) AS n FROM outbox WHERE needs_ack=1").first().int("n")
    private fun status(e: Engine, conv: String, id: String) = e.messages(conv).first { it["id"] == id }["status"]
    private fun texts(e: Engine, conv: String) = e.messages(conv).filter { it["kind"] == "text" }.map { it["body"] }

    private fun bothWays(a: Engine, b: Engine) {
        val ab = a.directConversation(b.myId); val ba = b.directConversation(a.myId)
        val m1 = a.sendText(ab, "A to B"); pump()
        val m2 = b.sendText(ba, "B to A"); pump()
        assertTrue(texts(b, ba).contains("A to B"))
        assertTrue(texts(a, ab).contains("B to A"))
        assertEquals("delivered", status(a, ab, m1))
        assertEquals("delivered", status(b, ba, m2))
        // Receipts came back, so nothing is left waiting in either queue.
        assertEquals(0, outbox(a)); assertEquals(0, outbox(b))
    }

    @Test fun singleChannelWorksBothWays() {
        val a = node("A"); val b = node("B")
        channel(a, "A", b, "B"); pump()
        bothWays(a, b)
    }

    @Test fun bothPhonesDialEachOtherThenDeduplicateAndStillWorkBothWays() {
        // Run with both id orders, since the kept channel depends on which contact id is lower.
        repeat(6) {
            queue.clear()
            val a = node("A$it"); val b = node("B$it")
            channel(a, "A", b, "B")
            channel(b, "B", a, "A")
            pump()
            assertEquals(listOf(b.myId), a.connectedPeerIds())
            assertEquals(listOf(a.myId), b.connectedPeerIds())
            bothWays(a, b)
        }
    }

    @Test fun messagesQueuedWhileApartAreDeliveredBothWaysAfterReconnect() {
        val a = node("A"); val b = node("B")
        val first = channel(a, "A", b, "B"); pump()
        val ab = a.directConversation(b.myId); val ba = b.directConversation(a.myId)
        first.close()
        assertTrue(a.connectedPeerIds().isEmpty() && b.connectedPeerIds().isEmpty())

        // Both write while out of range: shown as pending (clock), never as sent.
        val fromA = a.sendText(ab, "queued by A"); val fromB = b.sendText(ba, "queued by B")
        assertEquals("pending", status(a, ab, fromA)); assertEquals("pending", status(b, ba, fromB))

        // Back in range, this time B dials (roles reversed).
        channel(b, "B", a, "A"); pump()
        assertEquals("delivered", status(a, ab, fromA)); assertEquals("delivered", status(b, ba, fromB))
        assertEquals(0, outbox(a)); assertEquals(0, outbox(b))
    }

    @Test fun tickRetriesUntilDelivered() {
        val a = node("A"); val b = node("B")
        val ch = channel(a, "A", b, "B"); pump()
        val ab = a.directConversation(b.myId)
        // Lose every chunk of the first attempt (radio hiccup) without the link going down.
        val id = a.sendText(ab, "retry me"); queue.clear()
        assertEquals("sent", status(a, ab, id))
        now += 10_000; a.tick(); pump()
        assertEquals("sent", status(a, ab, id))   // backoff after 1 attempt is 30 s: not resent yet
        now += 25_000; a.tick(); pump()
        assertEquals("delivered", status(a, ab, id))
        assertEquals(0, outbox(a))
        ch.close()
    }
}

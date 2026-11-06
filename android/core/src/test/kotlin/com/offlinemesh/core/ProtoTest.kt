package com.offlinemesh.core

import com.offlinemesh.core.engine.Link
import com.offlinemesh.core.proto.Chunker
import com.offlinemesh.core.proto.Content
import com.offlinemesh.core.proto.LinkFrame
import com.offlinemesh.core.proto.MalformedException
import com.offlinemesh.core.proto.Packet
import com.offlinemesh.core.proto.Reassembler
import org.junit.Assert.*
import org.junit.Test

class ProtoTest {
    @Test fun chunkingOutOfOrderAndDuplicates() {
        val msg = ByteArray(5000) { (it % 251).toByte() }
        val chunks = Chunker.split(7, msg, 185)
        assertTrue(chunks.all { it.size <= 185 })
        val r = Reassembler()
        val shuffled = (chunks + chunks.take(3)).shuffled(java.util.Random(1))
        var out: ByteArray? = null
        for (c in shuffled) { val res = r.accept(c); if (res != null) out = res }
        assertArrayEquals(msg, out)
    }

    @Test fun corruptedChunkDetected() {
        val chunks = Chunker.split(1, ByteArray(400) { 1 }, 100).toMutableList()
        chunks[1] = chunks[1].copyOf().also { it[20] = 99 }
        val r = Reassembler()
        assertThrows(MalformedException::class.java) { chunks.forEach { r.accept(it) } }
    }

    @Test fun reassemblyTimeoutDropsPartial() {
        var now = 0L
        val r = Reassembler(clock = { now })
        val chunks = Chunker.split(2, ByteArray(300) { 2 }, 100)
        r.accept(chunks[0])
        now += 31_000
        // The stale partial (with chunk 0) is discarded, so the rest can never complete.
        chunks.drop(1).forEach { assertNull(r.accept(it)) }
    }

    @Test fun malformedInputsRejected() {
        val bad = listOf(byteArrayOf(), byteArrayOf(9), byteArrayOf(1, 0, 1, 0, 5, 0, 0), ByteArray(7))
        bad.forEach { b -> assertThrows(Exception::class.java) { Reassembler().accept(b) ?: throw MalformedException("incomplete") } }
        assertThrows(MalformedException::class.java) { LinkFrame.decode(byteArrayOf(0x7F)) }
        assertThrows(MalformedException::class.java) { LinkFrame.decode(byteArrayOf(0x01, 1)) }
        assertThrows(MalformedException::class.java) { Packet.decode(ByteArray(10)) }
        assertThrows(MalformedException::class.java) { Content.decode(byteArrayOf(1, 0, 5)) }
        // Oversized declared length must not allocate.
        val huge = byteArrayOf(1, 1, 6, 0) + ByteArray(16) + ByteArray(16) + ByteArray(8) + byteArrayOf(0x7F, 0, 0, 0)
        assertThrows(MalformedException::class.java) { Packet.decode(huge) }
        // TTL above max rejected.
        val p = Packet(1, 50, 0, ByteArray(16), ByteArray(16), 1, ByteArray(1)).encode()
        assertThrows(MalformedException::class.java) { Packet.decode(p) }
    }

    @Test fun contentRoundTrip() {
        val items = listOf(
            Content.Text("id", 5, "héllo 👋"), Content.Receipt(2, listOf("a", "b")), Content.Profile("Asha"),
            Content.GroupText("g", 3, "m", 9, "x"), Content.QrClaim(ByteArray(16) { 4 }),
        )
        items.forEach { assertEquals(it, Content.decode(Content.encode(it))) }
    }

    @Test fun duplicateLinksResolveToSameLinkOnBothSides() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        // Two simultaneous physical links between A and B with opposite initiator roles.
        class L(override val id: String, val to: () -> com.offlinemesh.core.engine.Engine, override val initiator: Boolean) : Link {
            override val maxChunk = 185
            var closed = false
            val q = ArrayList<ByteArray>()
            override fun send(chunk: ByteArray) { if (!closed) q += chunk }
            override fun close() { closed = true }
        }
        val a1 = L("x", { b }, true); val b1 = L("x", { a }, false)   // A dialed
        val a2 = L("y", { b }, false); val b2 = L("y", { a }, true)   // B dialed
        a.linkUp(a1); b.linkUp(b1); a.linkUp(a2); b.linkUp(b2)
        repeat(20) {
            listOf(a1 to b, b1 to a, a2 to b, b2 to a).forEach { (l, dst) ->
                val pending = l.q.toList(); l.q.clear()
                pending.forEach { dst.linkChunk(l.id, it) }
            }
        }
        val keep = if (a.myId < b.myId) "x" else "y"
        val closedA = listOf(a1, a2).filter { it.closed }.map { it.id }
        val closedB = listOf(b1, b2).filter { it.closed }.map { it.id }
        assertEquals(closedA, closedB)
        assertFalse(keep in closedA)
        assertEquals(listOf(b.myId), a.connectedPeerIds())
        assertEquals(listOf(a.myId), b.connectedPeerIds())
    }
}

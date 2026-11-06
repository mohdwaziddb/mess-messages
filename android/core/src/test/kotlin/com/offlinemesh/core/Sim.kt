package com.offlinemesh.core

import com.offlinemesh.core.engine.BlobStore
import com.offlinemesh.core.engine.Engine
import com.offlinemesh.core.engine.Link
import java.io.ByteArrayOutputStream

class MemBlobs : BlobStore {
    val chunks = HashMap<String, MutableMap<Int, ByteArray>>()
    val files = HashMap<String, ByteArray>()
    override fun writeChunk(attId: String, index: Int, data: ByteArray) { chunks.getOrPut(attId) { HashMap() }[index] = data }
    override fun assemble(attId: String, count: Int) {
        val out = ByteArrayOutputStream(); val c = chunks[attId] ?: return
        for (i in 0 until count) out.write(c[i] ?: return)
        files[attId] = out.toByteArray(); chunks.remove(attId)
    }
    override fun read(attId: String) = files[attId]
    override fun delete(attId: String) { files.remove(attId); chunks.remove(attId) }
}

/** Deterministic in-memory radio: chunks are queued and delivered by [pump]. */
class Net {
    var now = 1_800_000_000_000L
    val nodes = LinkedHashMap<String, Engine>()
    private val queue = ArrayDeque<Triple<Engine, String, ByteArray>>()
    private val pairs = HashMap<String, Pair<String, String>>() // linkKey -> (nodeA, nodeB)
    /** Optional interceptor: return null to drop, or modified bytes. */
    var tap: ((from: String, to: String, chunk: ByteArray) -> ByteArray?)? = null
    val captured = ArrayList<Triple<String, String, ByteArray>>()

    fun node(name: String, relay: Boolean = true): Engine {
        val e = Engine(JdbcSql(), MemBlobs(), { _, _ -> }, { now })
        e.createIdentity(name)
        e.relayEnabled = relay
        nodes[name] = e
        return e
    }

    private inner class SimLink(override val id: String, val from: String, val to: String) : Link {
        override val maxChunk = 185
        var open = true
        override fun send(chunk: ByteArray) {
            if (!open) return
            captured += Triple(from, to, chunk)
            val c = tap?.invoke(from, to, chunk) ?: if (tap != null) return else chunk
            queue.addLast(Triple(nodes[to]!!, id, c))
        }
        override fun close() { disconnect(from, to) }
    }

    private val links = HashMap<String, SimLink>()

    fun connect(a: String, b: String) {
        val key = listOf(a, b).sorted().joinToString("-")
        if (pairs.containsKey(key)) return
        pairs[key] = a to b
        val la = SimLink("$key", a, b); val lb = SimLink("$key", b, a)
        links["$a>$b"] = la; links["$b>$a"] = lb
        nodes[a]!!.linkUp(la); nodes[b]!!.linkUp(lb)
        pump()
    }

    fun disconnect(a: String, b: String) {
        val key = listOf(a, b).sorted().joinToString("-")
        pairs.remove(key) ?: return
        links.remove("$a>$b")?.open = false; links.remove("$b>$a")?.open = false
        nodes[a]!!.linkDown(key); nodes[b]!!.linkDown(key)
    }

    fun pump(max: Int = 200_000) {
        var n = 0
        while (queue.isNotEmpty() && n++ < max) {
            val (e, linkId, c) = queue.removeFirst()
            e.linkChunk(linkId, c)
        }
    }

    fun inject(to: String, linkFrom: String, chunks: List<ByteArray>) {
        val key = listOf(to, linkFrom).sorted().joinToString("-")
        chunks.forEach { queue.addLast(Triple(nodes[to]!!, key, it)) }
        pump()
    }

    fun tickAll(advanceMs: Long = 0) { now += advanceMs; nodes.values.forEach { it.tick() }; pump() }
}

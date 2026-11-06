package com.offlinemesh.core.proto

import java.util.zip.CRC32

/**
 * Link-level framing. A link message (≤ [MAX_MESSAGE]) is suffixed with a CRC32 (corruption
 * detection only — security comes from the end-to-end layer and signed handshake) and split
 * into chunks that fit a transport MTU (BLE ATT payload, or a socket write).
 *
 * Chunk = [ver u8][seq u16][index u16][count u16][data...]
 */
object Chunker {
    const val VERSION = 1
    const val HEADER = 7
    const val MAX_MESSAGE = 96 * 1024
    const val MAX_CHUNKS = 2048

    fun split(seq: Int, message: ByteArray, maxChunk: Int): List<ByteArray> {
        require(message.size <= MAX_MESSAGE) { "message too large: ${message.size}" }
        val dataPer = maxChunk - HEADER
        require(dataPer >= 8) { "mtu too small" }
        val crc = CRC32().apply { update(message) }.value
        val body = WireWriter(message.size + 4).fixed(message).u32(crc).toByteArray()
        val count = (body.size + dataPer - 1) / dataPer
        require(count <= MAX_CHUNKS) { "too many chunks" }
        return (0 until count).map { i ->
            val from = i * dataPer
            val to = minOf(body.size, from + dataPer)
            WireWriter(HEADER + to - from).u8(VERSION).u16(seq and 0xFFFF).u16(i).u16(count)
                .fixed(body.copyOfRange(from, to)).toByteArray()
        }
    }
}

/**
 * Per-link reassembly with hard bounds: limited in-flight messages, limited size, and a timeout
 * for partial messages. Corrupt or inconsistent chunks discard the partial message.
 */
class Reassembler(
    private val maxInFlight: Int = 4,
    private val timeoutMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Partial(val count: Int, val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val partials = LinkedHashMap<Int, Partial>()

    /** Returns a full message when complete, null otherwise. Throws [MalformedException] on bad input. */
    fun accept(chunk: ByteArray): ByteArray? {
        val r = WireReader(chunk)
        if (r.u8() != Chunker.VERSION) throw MalformedException("chunk version")
        val seq = r.u16(); val idx = r.u16(); val count = r.u16()
        if (count == 0 || count > Chunker.MAX_CHUNKS || idx >= count) throw MalformedException("chunk index")
        val data = r.rest()

        val now = clock()
        partials.entries.removeIf { now - it.value.startedAt > timeoutMs }

        var p = partials[seq]
        if (p == null || p.count != count) {
            if (partials.size >= maxInFlight) partials.remove(partials.keys.first())
            p = Partial(count, now)
            partials[seq] = p
        }
        if (p.parts[idx] == null) {
            p.bytes += data.size
            if (p.bytes > Chunker.MAX_MESSAGE + 4) { partials.remove(seq); throw MalformedException("oversize") }
            p.parts[idx] = data
            p.received++
        }
        if (p.received < p.count) return null
        partials.remove(seq)

        val body = WireWriter(p.bytes).apply { p.parts.forEach { fixed(it!!) } }.toByteArray()
        if (body.size < 4) throw MalformedException("short body")
        val msg = body.copyOfRange(0, body.size - 4)
        val crc = WireReader(body, body.size - 4).u32()
        if (CRC32().apply { update(msg) }.value != crc) throw MalformedException("crc mismatch")
        return msg
    }
}

package com.offlinemesh.core.mesh

import com.offlinemesh.core.db.Sql
import com.offlinemesh.core.db.one
import com.offlinemesh.core.proto.Hex
import com.offlinemesh.core.proto.Packet

/**
 * Store-and-forward relay limits. A relay only ever handles sealed packets it cannot decrypt.
 *  - per-neighbour rate limit (sliding window)
 *  - bounded queue (count + bytes), oldest evicted first
 *  - bounded forwarding fan-out per packet
 *  - pauses when the host reports low battery
 * Loop prevention is done by the engine's seen-packet cache plus the TTL hop limit.
 */
class RelayPolicy(private val clock: () -> Long) {
    var batteryOk = true
    var forwardedTotal = 0L
        private set

    private val windows = HashMap<String, ArrayDeque<Long>>()

    fun admit(db: Sql, fromLink: String, p: Packet, bytes: ByteArray): Boolean {
        if (!batteryOk) return false
        val now = clock()
        val w = windows.getOrPut(fromLink) { ArrayDeque() }
        while (w.isNotEmpty() && now - w.first() > WINDOW_MS) w.removeFirst()
        if (w.size >= MAX_PER_WINDOW) return false
        w.addLast(now)

        // Relays keep packets at most RELAY_HOLD_MS even if the sender asked for longer.
        val expires = minOf(p.expiresAt, now + RELAY_HOLD_MS)
        db.exec("INSERT OR IGNORE INTO relay_queue (packet_id, routing_tag, packet, from_link, received_at, expires_at) VALUES (?,?,?,?,?,?)",
            p.idHex(), Hex.enc(p.routingTag), bytes, fromLink, now, expires)
        enforceBounds(db)
        return true
    }

    fun updatePacket(db: Sql, packetId: String, bytes: ByteArray) =
        db.exec("UPDATE relay_queue SET packet=? WHERE packet_id=?", bytes, packetId)

    fun markForwarded(db: Sql, packetId: String) {
        db.exec("UPDATE relay_queue SET forwards=forwards+1 WHERE packet_id=?", packetId)
        forwardedTotal++
    }

    /** Packets still worth handing to newly met neighbours. */
    fun queued(db: Sql, now: Long): List<Triple<String, ByteArray, String>> {
        if (!batteryOk) return emptyList()
        return db.query("SELECT packet_id, packet, from_link FROM relay_queue WHERE expires_at > ? AND forwards < ? ORDER BY received_at DESC LIMIT 100", now, MAX_FORWARDS)
            .map { Triple(it.str("packet_id"), it.blob("packet"), it.str("from_link")) }
    }

    fun expire(db: Sql, now: Long) {
        db.exec("DELETE FROM relay_queue WHERE expires_at < ? OR forwards >= ?", now, MAX_FORWARDS)
        windows.values.forEach { w -> while (w.isNotEmpty() && now - w.first() > WINDOW_MS) w.removeFirst() }
        windows.entries.removeIf { it.value.isEmpty() }
    }

    private fun enforceBounds(db: Sql) {
        val count = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(length(packet)),0) AS b FROM relay_queue")!!
        if (count.int("n") > MAX_QUEUE || count.long("b") > MAX_BYTES) {
            db.exec("DELETE FROM relay_queue WHERE packet_id IN (SELECT packet_id FROM relay_queue ORDER BY received_at ASC LIMIT ?)",
                maxOf(1, count.int("n") / 10))
        }
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_PER_WINDOW = 120
        const val MAX_QUEUE = 500
        const val MAX_BYTES = 16L * 1024 * 1024
        const val MAX_FORWARDS = 20
        const val RELAY_HOLD_MS = 24L * 3600_000
    }
}

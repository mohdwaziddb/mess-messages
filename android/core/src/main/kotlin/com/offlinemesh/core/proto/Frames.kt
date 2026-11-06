package com.offlinemesh.core.proto

/** Messages exchanged on a single direct link (after reassembly). */
sealed class LinkFrame {
    data class Hello(val version: Int, val nonce: ByteArray, val caps: Int) : LinkFrame()

    /** Identity proof: signature by the identity key over both nonces + identity + bundle. */
    data class Auth(val identityKey: ByteArray, val bundle: ByteArray, val signature: ByteArray) : LinkFrame()

    data class AuthResult(val code: Int) : LinkFrame()

    /** Opaque mesh packet (see [Packet]). */
    data class Data(val packet: ByteArray) : LinkFrame()

    object Ping : LinkFrame()

    companion object {
        const val PROTOCOL_VERSION = 1
        const val T_HELLO = 0x01
        const val T_AUTH = 0x02
        const val T_AUTH_RESULT = 0x03
        const val T_DATA = 0x10
        const val T_PING = 0x11

        const val CAP_RELAY = 0x0001
        const val CAP_WIFI_DIRECT = 0x0002
        const val CAP_WIFI_AWARE = 0x0004

        const val AUTH_OK = 0
        const val AUTH_REJECTED = 1
        const val AUTH_BLOCKED = 2

        fun encode(f: LinkFrame): ByteArray = when (f) {
            is Hello -> WireWriter().u8(T_HELLO).u8(f.version).fixed(f.nonce).u16(f.caps).toByteArray()
            is Auth -> WireWriter(2048).u8(T_AUTH).bytes16(f.identityKey).bytes16(f.bundle).bytes16(f.signature).toByteArray()
            is AuthResult -> WireWriter().u8(T_AUTH_RESULT).u8(f.code).toByteArray()
            is Data -> WireWriter(f.packet.size + 1).u8(T_DATA).fixed(f.packet).toByteArray()
            Ping -> byteArrayOf(T_PING.toByte())
        }

        fun decode(b: ByteArray): LinkFrame {
            val r = WireReader(b)
            val f = when (r.u8()) {
                T_HELLO -> Hello(r.u8(), r.fixed(32), r.u16())
                T_AUTH -> Auth(r.bytes16(), r.bytes16(), r.bytes16())
                T_AUTH_RESULT -> AuthResult(r.u8())
                T_DATA -> Data(r.rest())
                T_PING -> Ping
                else -> throw MalformedException("unknown frame type")
            }
            r.expectEnd()
            return f
        }
    }
}

/**
 * Mesh packet. The header is visible to relays; the payload is an end-to-end sealed envelope.
 *
 * [ver u8][type u8][ttl u8][hops u8][packetId 16][routingTag 16][expiresAt u64][payload bytes32]
 *
 * Relays may only decrement ttl / increment hops. Everything else is bound into the envelope's
 * AEAD associated data ([aad]) so tampering by a relay causes decryption failure.
 */
data class Packet(
    val type: Int,
    val ttl: Int,
    val hops: Int,
    val packetId: ByteArray,
    val routingTag: ByteArray,
    val expiresAt: Long,
    val payload: ByteArray,
) {
    fun encode(): ByteArray = WireWriter(HEADER + payload.size).u8(VERSION).u8(type).u8(ttl).u8(hops)
        .fixed(packetId).fixed(routingTag).u64(expiresAt).bytes32(payload).toByteArray()

    fun aad(): ByteArray = WireWriter(48).u8(VERSION).u8(type).fixed(packetId).fixed(routingTag).u64(expiresAt).toByteArray()

    fun idHex(): String = Hex.enc(packetId)

    companion object {
        const val VERSION = 1
        const val TYPE_ENVELOPE = 1
        const val HEADER = 4 + 16 + 16 + 8 + 4
        const val MAX_PAYLOAD = 64 * 1024
        const val MAX_TTL = 8
        const val DEFAULT_TTL = 6

        fun decode(b: ByteArray): Packet {
            if (b.size > HEADER + MAX_PAYLOAD) throw MalformedException("packet too large")
            val r = WireReader(b)
            if (r.u8() != VERSION) throw MalformedException("packet version")
            val type = r.u8()
            if (type != TYPE_ENVELOPE) throw MalformedException("packet type")
            val ttl = r.u8(); val hops = r.u8()
            if (ttl > MAX_TTL || hops > MAX_TTL) throw MalformedException("ttl")
            val p = Packet(type, ttl, hops, r.fixed(16), r.fixed(16), r.u64(), r.bytes32(MAX_PAYLOAD))
            r.expectEnd()
            return p
        }
    }
}

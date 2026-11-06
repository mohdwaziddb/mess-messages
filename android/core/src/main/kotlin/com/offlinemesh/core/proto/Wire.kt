package com.offlinemesh.core.proto

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Thrown for any malformed / oversized / truncated input. Callers drop the packet. */
class MalformedException(msg: String) : Exception(msg)

/** Minimal big-endian binary writer. Every variable-length field is length-prefixed. */
class WireWriter(initial: Int = 64) {
    private val out = ByteArrayOutputStream(initial)

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    fun u32(v: Long) = apply { for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
    fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
    fun fixed(b: ByteArray) = apply { out.write(b) }
    fun bytes16(b: ByteArray) = apply { require(b.size <= 0xFFFF) { "field too large" }; u16(b.size); out.write(b) }
    fun bytes32(b: ByteArray) = apply { u32(b.size.toLong()); out.write(b) }
    fun str(s: String) = bytes16(s.toByteArray(StandardCharsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Bounds-checked reader. Never trusts a length field beyond [maxField]. */
class WireReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw MalformedException("truncated: need $n at $pos of $end")
    }

    fun remaining() = end - pos
    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); return (u8() shl 8) or u8() }
    fun u32(): Long { need(4); var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; return v }
    fun u64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }
    fun fixed(n: Int): ByteArray { need(n); return buf.copyOfRange(pos, pos + n).also { pos += n } }
    fun bytes16(): ByteArray = fixed(u16())
    fun bytes32(maxField: Int): ByteArray {
        val n = u32()
        if (n > maxField) throw MalformedException("field $n > $maxField")
        return fixed(n.toInt())
    }
    fun str(maxChars: Int = 4096): String {
        val s = String(bytes16(), StandardCharsets.UTF_8)
        if (s.length > maxChars) throw MalformedException("string too long")
        return s
    }
    fun rest(): ByteArray = fixed(remaining())
    fun expectEnd() { if (pos != end) throw MalformedException("trailing ${end - pos} bytes") }
}

object Hex {
    private val digits = "0123456789abcdef".toCharArray()
    fun enc(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        b.forEachIndexed { i, v -> c[i * 2] = digits[(v.toInt() ushr 4) and 0xF]; c[i * 2 + 1] = digits[v.toInt() and 0xF] }
        return String(c)
    }
    fun dec(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex" }
        return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }
    }
}

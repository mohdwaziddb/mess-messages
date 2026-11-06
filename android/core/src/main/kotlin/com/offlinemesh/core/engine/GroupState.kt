package com.offlinemesh.core.engine

import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.proto.MalformedException
import com.offlinemesh.core.proto.WireReader
import com.offlinemesh.core.proto.WireWriter
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.UUID

/**
 * Full group state at one epoch, signed by the admin (or leaving member) who produced it.
 * Every membership change produces a new epoch; members only accept a new state if it is signed
 * by an admin of the state they currently hold (or is a self-removal), and the epoch increases.
 */
data class GroupState(
    val groupId: String,
    val epoch: Int,
    val name: String,
    val members: List<Member>,
    val admins: Set<String>,
    val opType: Int,
    val issuerId: String,
    val issuedAt: Long,
    val signature: ByteArray = ByteArray(0),
) {
    data class Member(val id: String, val identityKey: ByteArray, val name: String)

    fun memberIds(): Set<String> = members.map { it.id }.toSet()
    fun isMember(id: String) = members.any { it.id == id }
    fun isAdmin(id: String) = id in admins && isMember(id)
    fun member(id: String) = members.firstOrNull { it.id == id }

    fun distributionId(): UUID = UUID.nameUUIDFromBytes("$groupId/$epoch".toByteArray())

    private fun body(): ByteArray {
        val w = WireWriter(512).u8(1).str(groupId).u32(epoch.toLong()).str(name).u8(opType).str(issuerId).u64(issuedAt)
        w.u16(members.size)
        members.sortedBy { it.id }.forEach { w.str(it.id).bytes16(it.identityKey).str(it.name) }
        val a = admins.sorted()
        w.u16(a.size); a.forEach { w.str(it) }
        return w.toByteArray()
    }

    fun signedBy(identity: IdentityKeyPair): GroupState = copy(signature = identity.privateKey.calculateSignature(body()))

    fun encode(): ByteArray = WireWriter(1024).bytes32(body()).bytes16(signature).toByteArray()

    /** Signature check + internal consistency (member ids really are hashes of their identity keys). */
    fun verify(issuerKey: ByteArray? = null): Boolean {
        val keyBytes = issuerKey ?: member(issuerId)?.identityKey ?: return false
        val key = runCatching { IdentityKey(keyBytes) }.getOrNull() ?: return false
        if (!key.publicKey.verifySignature(body(), signature)) return false
        return members.all { m -> runCatching { Crypto.contactId(IdentityKey(m.identityKey)) == m.id }.getOrDefault(false) }
    }

    companion object {
        const val OP_CREATE = 1
        const val OP_ADD = 2
        const val OP_REMOVE = 3
        const val OP_LEAVE = 4
        const val OP_ADMIN = 5
        const val OP_RENAME = 6
        const val MAX_MEMBERS = 64

        fun decode(bytes: ByteArray): GroupState {
            val outer = WireReader(bytes)
            val body = outer.bytes32(32 * 1024)
            val sig = outer.bytes16()
            outer.expectEnd()
            val r = WireReader(body)
            if (r.u8() != 1) throw MalformedException("group state version")
            val groupId = r.str(64); val epoch = r.u32().toInt(); val name = r.str(64); val op = r.u8()
            val issuer = r.str(64); val at = r.u64()
            val n = r.u16()
            if (n > MAX_MEMBERS) throw MalformedException("too many members")
            val members = List(n) { Member(r.str(64), r.bytes16(), r.str(64)) }
            val na = r.u16()
            if (na > n) throw MalformedException("too many admins")
            val admins = List(na) { r.str(64) }.toSet()
            r.expectEnd()
            return GroupState(groupId, epoch, name, members, admins, op, issuer, at, sig)
        }

        /**
         * Is [next] a legitimate successor of [current]?
         * - epoch must strictly increase;
         * - signature valid;
         * - issuer is an admin in [current], or it's a LEAVE where the issuer removed only itself.
         */
        fun isValidTransition(current: GroupState, next: GroupState): Boolean {
            if (next.groupId != current.groupId || next.epoch <= current.epoch) return false
            val issuerKey = current.member(next.issuerId)?.identityKey ?: next.member(next.issuerId)?.identityKey ?: return false
            if (Crypto.contactId(IdentityKey(issuerKey)) != next.issuerId || !next.verify(issuerKey)) return false
            if (current.isAdmin(next.issuerId)) return true
            if (next.opType == OP_LEAVE && current.isMember(next.issuerId)) {
                val expected = current.memberIds() - next.issuerId
                return next.memberIds() == expected && next.admins == (current.admins - next.issuerId) && next.name == current.name
            }
            return false
        }
    }
}

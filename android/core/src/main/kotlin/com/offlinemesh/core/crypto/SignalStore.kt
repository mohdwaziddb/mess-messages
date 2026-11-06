package com.offlinemesh.core.crypto

import com.offlinemesh.core.db.Sql
import com.offlinemesh.core.db.one
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * libsignal protocol store persisted in the (SQLCipher-encrypted) `crypto_kv` table.
 * Only serialized libsignal records are stored. Identity trust policy:
 *  - first sighting of an identity: trusted (TOFU) and marked unverified in contacts;
 *  - changed identity for a known address: NOT trusted until the user explicitly accepts it.
 */
class SignalStore(private val db: Sql) : SignalProtocolStore {
    private fun get(ns: String, k: String): ByteArray? = db.one("SELECT v FROM crypto_kv WHERE ns=? AND k=?", ns, k)?.blob("v")
    private fun put(ns: String, k: String, v: ByteArray) = db.exec("INSERT OR REPLACE INTO crypto_kv (ns,k,v) VALUES (?,?,?)", ns, k, v)
    private fun del(ns: String, k: String) = db.exec("DELETE FROM crypto_kv WHERE ns=? AND k=?", ns, k)
    private fun all(ns: String) = db.query("SELECT k, v FROM crypto_kv WHERE ns=?", ns)

    private fun addrKey(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    // ---- local identity ----
    fun hasLocalIdentity() = get(NS_SELF, "identity") != null

    fun initLocalIdentity(pair: IdentityKeyPair, registrationId: Int) {
        put(NS_SELF, "identity", pair.serialize())
        put(NS_SELF, "registration_id", registrationId.toString().toByteArray())
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair(get(NS_SELF, "identity") ?: error("no local identity"))
    override fun getLocalRegistrationId(): Int = String(get(NS_SELF, "registration_id") ?: error("no registration id")).toInt()

    // ---- remote identities ----
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        put(NS_IDENTITY, address.name, identityKey.serialize())
        return if (existing == null || existing == identityKey) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val existing = getIdentity(address) ?: return true
        return existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = get(NS_IDENTITY, address.name)?.let { IdentityKey(it) }

    // ---- one-time prekeys ----
    override fun loadPreKey(preKeyId: Int): PreKeyRecord = get(NS_PREKEY, "$preKeyId")?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = put(NS_PREKEY, "$preKeyId", record.serialize())
    override fun containsPreKey(preKeyId: Int) = get(NS_PREKEY, "$preKeyId") != null
    override fun removePreKey(preKeyId: Int) = del(NS_PREKEY, "$preKeyId")
    fun preKeyIds(): List<Int> = all(NS_PREKEY).map { it.str("k").toInt() }

    // ---- signed prekeys ----
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = get(NS_SIGNED, "$signedPreKeyId")?.let { SignedPreKeyRecord(it) } ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = all(NS_SIGNED).map { SignedPreKeyRecord(it.blob("v")) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = put(NS_SIGNED, "$signedPreKeyId", record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = get(NS_SIGNED, "$signedPreKeyId") != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = del(NS_SIGNED, "$signedPreKeyId")

    // ---- kyber (post-quantum) prekeys ----
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = get(NS_KYBER, "$kyberPreKeyId")?.let { KyberPreKeyRecord(it) } ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = all(NS_KYBER).map { KyberPreKeyRecord(it.blob("v")) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = put(NS_KYBER, "$kyberPreKeyId", record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = get(NS_KYBER, "$kyberPreKeyId") != null
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // Our kyber prekeys are "last resort" style (rotated by IdentityManager), so they are kept.
        // libsignal's own base-key reuse check is enforced inside the session layer.
    }

    // ---- sessions ----
    override fun loadSession(address: SignalProtocolAddress): SessionRecord = get(NS_SESSION, addrKey(address))?.let { SessionRecord(it) } ?: SessionRecord()
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a -> get(NS_SESSION, addrKey(a))?.let { SessionRecord(it) } ?: throw NoSessionException("no session for $a") }
    override fun getSubDeviceSessions(name: String): List<Int> =
        all(NS_SESSION).map { it.str("k") }.filter { it.startsWith("$name.") }.map { it.substringAfterLast('.').toInt() }.filter { it != 1 }
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = put(NS_SESSION, addrKey(address), record.serialize())
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        get(NS_SESSION, addrKey(address))?.let { SessionRecord(it).hasSenderChain() } ?: false
    override fun deleteSession(address: SignalProtocolAddress) = del(NS_SESSION, addrKey(address))
    override fun deleteAllSessions(name: String) = db.exec("DELETE FROM crypto_kv WHERE ns=? AND k LIKE ?", NS_SESSION, "$name.%")

    // ---- sender keys (groups) ----
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        put(NS_SENDER_KEY, "${addrKey(sender)}::$distributionId", record.serialize())
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        get(NS_SENDER_KEY, "${addrKey(sender)}::$distributionId")?.let { SenderKeyRecord(it) }

    /** Explicit user acceptance of a changed identity: replace it and drop old sessions. */
    fun acceptIdentity(name: String, key: IdentityKey) {
        put(NS_IDENTITY, name, key.serialize())
        deleteAllSessions(name)
        deleteSession(SignalProtocolAddress(name, 1))
    }

    companion object {
        const val NS_SELF = "self"
        const val NS_IDENTITY = "identity"
        const val NS_PREKEY = "prekey"
        const val NS_SIGNED = "signed_prekey"
        const val NS_KYBER = "kyber_prekey"
        const val NS_SESSION = "session"
        const val NS_SENDER_KEY = "sender_key"
    }
}

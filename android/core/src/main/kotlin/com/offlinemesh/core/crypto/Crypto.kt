package com.offlinemesh.core.crypto

import com.offlinemesh.core.proto.Hex
import com.offlinemesh.core.proto.MalformedException
import com.offlinemesh.core.proto.WireReader
import com.offlinemesh.core.proto.WireWriter
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * All cryptography is delegated to libsignal (X25519/XEdDSA, PQXDH, Double Ratchet, Sender Keys,
 * HPKE seal) and the platform JCA (SHA-256, HMAC-SHA256). Nothing here invents a primitive.
 */
object Crypto {
    const val DEVICE_ID = 1
    private const val PREKEY_BATCH = 100
    private const val PREKEY_LOW_WATER = 20
    private const val SIGNED_PREKEY_ID = 1
    private const val KYBER_PREKEY_ID = 1
    private val random = SecureRandom()

    private val LINK_AUTH_LABEL = "OfflineMesh-v1-link-auth".toByteArray()
    private val ENVELOPE_INFO = "OfflineMesh-v1-envelope".toByteArray()
    private val ROUTE_LABEL = "OfflineMesh-v1-route".toByteArray()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** Stable contact id derived from the identity public key (never from the username). */
    fun contactId(identity: IdentityKey): String = Hex.enc(sha256(identity.serialize()))

    /** Human-readable fingerprint of one identity, e.g. for "My QR" screen. */
    fun displayFingerprint(identity: IdentityKey): String =
        Hex.enc(sha256(identity.serialize())).take(40).uppercase().chunked(4).joinToString(" ")

    // ------------------------------------------------------------------ identity lifecycle

    fun createIdentity(store: SignalStore) {
        check(!store.hasLocalIdentity()) { "identity already exists" }
        val identity = IdentityKeyPair.generate()
        store.initLocalIdentity(identity, KeyHelper.generateRegistrationId(false))
        rotateSignedPreKeys(store)
        replenishPreKeys(store)
    }

    /** Creates a fresh signed prekey and kyber last-resort prekey, both signed by the identity key. */
    fun rotateSignedPreKeys(store: SignalStore) {
        val identity = store.identityKeyPair
        val now = System.currentTimeMillis()
        val spk = ECKeyPair.generate()
        store.storeSignedPreKey(SIGNED_PREKEY_ID, SignedPreKeyRecord(SIGNED_PREKEY_ID, now, spk, identity.privateKey.calculateSignature(spk.publicKey.serialize())))
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        store.storeKyberPreKey(KYBER_PREKEY_ID, KyberPreKeyRecord(KYBER_PREKEY_ID, now, kyber, identity.privateKey.calculateSignature(kyber.publicKey.serialize())))
    }

    fun replenishPreKeys(store: SignalStore) {
        val ids = store.preKeyIds()
        if (ids.size >= PREKEY_LOW_WATER) return
        val next = (ids.maxOrNull() ?: 0) + 1
        for (i in 0 until PREKEY_BATCH) {
            val id = ((next + i - 1) % 0xFFFFFF) + 1
            store.storePreKey(id, PreKeyRecord(id, ECKeyPair.generate()))
        }
    }

    /** Our current bundle, serialized for the link handshake. */
    fun localBundle(store: SignalStore): ByteArray {
        replenishPreKeys(store)
        val preKeyId = store.preKeyIds().min()
        val pre = store.loadPreKey(preKeyId)
        val spk = store.loadSignedPreKey(SIGNED_PREKEY_ID)
        val kyber = store.loadKyberPreKey(KYBER_PREKEY_ID)
        return WireWriter(2048)
            .u32(store.localRegistrationId.toLong())
            .u32(preKeyId.toLong()).bytes16(pre.keyPair.publicKey.serialize())
            .u32(spk.id.toLong()).bytes16(spk.keyPair.publicKey.serialize()).bytes16(spk.signature)
            .u32(kyber.id.toLong()).bytes16(kyber.keyPair.publicKey.serialize()).bytes16(kyber.signature)
            .toByteArray()
    }

    fun parseBundle(bytes: ByteArray, identity: IdentityKey): PreKeyBundle {
        val r = WireReader(bytes)
        val regId = r.u32().toInt()
        val preId = r.u32().toInt(); val pre = ECPublicKey(r.bytes16())
        val spkId = r.u32().toInt(); val spk = ECPublicKey(r.bytes16()); val spkSig = r.bytes16()
        val kId = r.u32().toInt(); val kPub = KEMPublicKey(r.bytes16()); val kSig = r.bytes16()
        r.expectEnd()
        // libsignal re-verifies both signatures in SessionBuilder.process(); we check early to fail fast.
        if (!identity.publicKey.verifySignature(spk.serialize(), spkSig)) throw MalformedException("bad signed prekey signature")
        if (!identity.publicKey.verifySignature(kPub.serialize(), kSig)) throw MalformedException("bad kyber prekey signature")
        return PreKeyBundle(regId, DEVICE_ID, preId, pre, spkId, spk, spkSig, identity, kId, kPub, kSig)
    }

    // ------------------------------------------------------------------ link authentication

    private fun authTranscript(signerNonce: ByteArray, otherNonce: ByteArray, identity: ByteArray, bundle: ByteArray) =
        WireWriter(512).fixed(LINK_AUTH_LABEL).fixed(signerNonce).fixed(otherNonce).bytes16(identity).bytes32(bundle).toByteArray()

    fun signLinkAuth(store: SignalStore, myNonce: ByteArray, peerNonce: ByteArray, bundle: ByteArray): ByteArray {
        val id = store.identityKeyPair
        return id.privateKey.calculateSignature(authTranscript(myNonce, peerNonce, id.publicKey.serialize(), bundle))
    }

    fun verifyLinkAuth(identity: IdentityKey, peerNonce: ByteArray, myNonce: ByteArray, bundle: ByteArray, sig: ByteArray): Boolean =
        identity.publicKey.verifySignature(authTranscript(peerNonce, myNonce, identity.serialize(), bundle), sig)

    // ------------------------------------------------------------------ routing + envelope

    fun dayIndex(nowMs: Long) = nowMs / 86_400_000L

    /** Opaque 16-byte tag identifying the recipient for a given day. Relays learn nothing else. */
    fun routingTag(recipient: IdentityKey, day: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(recipient.serialize(), "HmacSHA256"))
        mac.update(ROUTE_LABEL)
        mac.update(WireWriter().u64(day).toByteArray())
        return mac.doFinal().copyOf(16)
    }

    /** Tags under which we accept packets (yesterday/today/tomorrow to tolerate clock skew). */
    fun myRoutingTags(me: IdentityKey, nowMs: Long): Set<String> =
        (-1L..1L).map { Hex.enc(routingTag(me, dayIndex(nowMs) + it)) }.toSet()

    /**
     * Hides sender identity and message type from relays: HPKE-seal (libsignal) to the recipient's
     * identity key, with the packet header as associated data.
     */
    fun sealEnvelope(recipient: IdentityKey, sender: IdentityKey, signalType: Int, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val inner = WireWriter(ciphertext.size + 64).bytes16(sender.serialize()).u8(signalType).bytes32(ciphertext).toByteArray()
        return recipient.publicKey.seal(inner, ENVELOPE_INFO, aad)
    }

    data class Opened(val sender: IdentityKey, val signalType: Int, val ciphertext: ByteArray)

    fun openEnvelope(store: SignalStore, sealed: ByteArray, aad: ByteArray): Opened {
        val inner = store.identityKeyPair.privateKey.open(sealed, ENVELOPE_INFO, aad)
        val r = WireReader(inner)
        val o = Opened(IdentityKey(r.bytes16()), r.u8(), r.bytes32(64 * 1024))
        r.expectEnd()
        return o
    }

    // ------------------------------------------------------------------ verification

    /** 60-digit safety number, identical on both sides (Signal's numeric fingerprint v2). */
    fun safetyNumber(local: IdentityKey, remote: IdentityKey): String {
        val fp = NumericFingerprintGenerator(5200).createFor(
            2, Hex.dec(contactId(local)), local, Hex.dec(contactId(remote)), remote,
        )
        return fp.displayableFingerprint.displayText
    }

    // ------------------------------------------------------------------ QR pairing

    data class QrPayload(val token: ByteArray, val fingerprint: ByteArray, val ephemeralId: ByteArray, val expiresAt: Long)

    const val QR_PREFIX = "om1:"
    const val QR_TTL_MS = 5 * 60_000L

    /** QR = minimum info: one-time token, identity fingerprint (hash of public key), current BLE id, expiry. */
    fun encodeQr(p: QrPayload): String = QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
        WireWriter(80).u8(1).fixed(p.token).fixed(p.fingerprint).fixed(p.ephemeralId).u64(p.expiresAt).toByteArray(),
    )

    fun decodeQr(s: String): QrPayload {
        if (!s.startsWith(QR_PREFIX)) throw MalformedException("not an Offline Mesh code")
        val b = try { Base64.getUrlDecoder().decode(s.removePrefix(QR_PREFIX)) } catch (e: IllegalArgumentException) { throw MalformedException("bad QR encoding") }
        val r = WireReader(b)
        if (r.u8() != 1) throw MalformedException("unsupported QR version")
        val p = QrPayload(r.fixed(16), r.fixed(32), r.fixed(8), r.u64())
        r.expectEnd()
        return p
    }
}

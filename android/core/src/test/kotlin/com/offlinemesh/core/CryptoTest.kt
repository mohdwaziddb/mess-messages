package com.offlinemesh.core

import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.crypto.SignalStore
import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.DuplicateMessageException

class CryptoTest {
    private fun newStore() = SignalStore(JdbcSql()).also { Crypto.createIdentity(it) }

    @Test fun sessionRoundTripAndReplayRejected() {
        val a = newStore(); val b = newStore()
        val aId = a.identityKeyPair.publicKey; val bId = b.identityKeyPair.publicKey
        val aAddr = SignalProtocolAddress(Crypto.contactId(aId), 1)
        val bAddr = SignalProtocolAddress(Crypto.contactId(bId), 1)

        SessionBuilder(a, bAddr, aAddr).process(Crypto.parseBundle(Crypto.localBundle(b), bId))
        val c1 = SessionCipher(a, aAddr, bAddr).encrypt("hello".toByteArray())
        val m1 = PreKeySignalMessage(c1.serialize())
        assertEquals("hello", String(SessionCipher(b, bAddr, aAddr).decrypt(m1)))
        assertThrows(DuplicateMessageException::class.java) { SessionCipher(b, bAddr, aAddr).decrypt(PreKeySignalMessage(c1.serialize())) }

        val c2 = SessionCipher(b, bAddr, aAddr).encrypt("hi back".toByteArray())
        assertEquals("hi back", String(SessionCipher(a, aAddr, bAddr).decrypt(SignalMessage(c2.serialize()))))
    }

    @Test fun envelopeSealOpenAndTamper() {
        val a = newStore(); val b = newStore()
        val aad = "header".toByteArray()
        val sealed = Crypto.sealEnvelope(b.identityKeyPair.publicKey, a.identityKeyPair.publicKey, 2, byteArrayOf(1, 2, 3), aad)
        val o = Crypto.openEnvelope(b, sealed, aad)
        assertEquals(a.identityKeyPair.publicKey, o.sender)
        assertArrayEquals(byteArrayOf(1, 2, 3), o.ciphertext)
        assertThrows(Exception::class.java) { Crypto.openEnvelope(b, sealed, "other".toByteArray()) }
        assertThrows(Exception::class.java) { Crypto.openEnvelope(a, sealed, aad) } // wrong recipient (e.g. a relay)
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertThrows(Exception::class.java) { Crypto.openEnvelope(b, sealed, aad) }
    }

    @Test fun safetyNumberSymmetricAndLinkAuth() {
        val a = newStore(); val b = newStore()
        val ak = a.identityKeyPair.publicKey; val bk = b.identityKeyPair.publicKey
        assertEquals(Crypto.safetyNumber(ak, bk), Crypto.safetyNumber(bk, ak))
        assertEquals(60, Crypto.safetyNumber(ak, bk).length)
        val na = Crypto.randomBytes(32); val nb = Crypto.randomBytes(32)
        val bundle = Crypto.localBundle(a)
        val sig = Crypto.signLinkAuth(a, na, nb, bundle)
        assertTrue(Crypto.verifyLinkAuth(ak, na, nb, bundle, sig))
        assertFalse(Crypto.verifyLinkAuth(ak, nb, na, bundle, sig))   // swapped nonces = replay elsewhere
        assertFalse(Crypto.verifyLinkAuth(bk, na, nb, bundle, sig))   // wrong identity
    }

    @Test fun qrRoundTrip() {
        val p = Crypto.QrPayload(Crypto.randomBytes(16), Crypto.randomBytes(32), Crypto.randomBytes(8), 123L)
        val d = Crypto.decodeQr(Crypto.encodeQr(p))
        assertArrayEquals(p.token, d.token); assertArrayEquals(p.fingerprint, d.fingerprint); assertEquals(123L, d.expiresAt)
        assertThrows(Exception::class.java) { Crypto.decodeQr("https://example.com") }
    }
}

package com.offlinemesh.storage

import com.offlinemesh.core.engine.BlobStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Attachments at rest: every file and partial chunk is AES-256-GCM encrypted with a Keystore-wrapped key. */
class EncryptedBlobStore(private val dir: File, private val key: ByteArray) : BlobStore {
    init { dir.mkdirs() }
    private val rnd = SecureRandom()
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun enc(plain: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return iv + c.doFinal(plain)
    }

    private fun dec(blob: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun chunkFile(id: String, i: Int) = File(dir, "${safe(id)}.part$i")
    private fun file(id: String) = File(dir, "${safe(id)}.bin")

    override fun writeChunk(attId: String, index: Int, data: ByteArray) = chunkFile(attId, index).writeBytes(enc(data, "$attId#$index"))

    override fun assemble(attId: String, count: Int) {
        val out = ByteArrayOutputStream()
        for (i in 0 until count) {
            val f = chunkFile(attId, i)
            if (!f.exists()) return
            out.write(dec(f.readBytes(), "$attId#$i"))
        }
        file(attId).writeBytes(enc(out.toByteArray(), attId))
        for (i in 0 until count) chunkFile(attId, i).delete()
    }

    override fun read(attId: String): ByteArray? =
        file(attId).takeIf { it.exists() }?.let { runCatching { dec(it.readBytes(), attId) }.getOrNull() }

    override fun delete(attId: String) {
        file(attId).delete()
        dir.listFiles { f -> f.name.startsWith("${safe(attId)}.part") }?.forEach { it.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

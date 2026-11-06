package com.offlinemesh.storage

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates random 32-byte secrets (database passphrase, file key) and stores them only in
 * wrapped form: AES-256-GCM under a non-exportable Android Keystore key (StrongBox when the
 * device has it). The wrapped blob lives in noBackupFilesDir, which is never backed up.
 */
class KeyWrap(private val context: Context) {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrappingKey(alias: String): SecretKey {
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        fun gen(strongBox: Boolean): SecretKey {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
        }
        // StrongBox is optional hardware; fall back to the TEE-backed (or software) keystore.
        return try { gen(strongBox = true) } catch (e: Exception) { gen(strongBox = false) }
    }

    /** Returns the secret named [name], creating and wrapping a fresh random one on first use. */
    fun secret(name: String): ByteArray {
        val alias = "offlinemesh.$name.wrap"
        val file = File(context.noBackupFilesDir, "$name.wrapped")
        val key = wrappingKey(alias)
        if (file.exists()) {
            val blob = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        check(iv.size == 12) { "unexpected IV size" }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(iv + c.doFinal(secret))
        if (!tmp.renameTo(file)) throw IllegalStateException("could not store wrapped key")
        return secret
    }

    /** True if the wrapping key lives in secure hardware (TEE or StrongBox). */
    fun isHardwareBacked(name: String): Boolean = try {
        val k = wrappingKey("offlinemesh.$name.wrap")
        val info = SecretKeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
        else @Suppress("DEPRECATION") info.isInsideSecureHardware
    } catch (e: Exception) { false }
}

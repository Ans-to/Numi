package com.calcvault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small records encrypted with an app-private, non-exportable Android Keystore key. */
class SecretStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "security").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "calcvault.root.v1"
    @Synchronized private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(root.listFiles().isNullOrEmpty()) { "Encryption key missing. Existing data cannot be opened. Do not clear app storage." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun exists(name: String) = File(root, name).exists() || File(root, "$name.bak").exists()
    @Synchronized fun read(name: String): ByteArray? {
        if (!exists(name)) return null
        val bytes = AtomicFile(File(root, name)).readFully()
        require(bytes.size in 29..16_777_216) { "Invalid security record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        require(bytes[0] == 1.toByte())
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }
    @Synchronized fun write(name: String, plaintext: ByteArray) {
        require(plaintext.size <= 16_777_187) { "Vault index capacity reached" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        val atomic = AtomicFile(File(root, name))
        val out = atomic.startWrite()
        try { out.write(encrypted); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}

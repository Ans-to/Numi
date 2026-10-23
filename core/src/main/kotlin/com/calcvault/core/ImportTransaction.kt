package com.calcvault.core

import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Ciphertext is published only after full readback authentication and byte-for-byte digest verification. */
object ImportTransaction {
    data class Prepared(val id: String, val file: File, val key: SecretKey, val size: Long, val digest: ByteArray)
    fun prepare(directory: File, input: InputStream, cancelled: () -> Boolean = { false }): Prepared {
        check(directory.isDirectory || directory.mkdirs()) { "Private storage unavailable" }
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.partial")
        val destination = File(directory, "$id.cv")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            val written = ChunkCipher.encrypt(input, partial, key, id, cancelled)
            ChunkCipher.Reader(partial, key, id, written.size).use { it.verify(written.digest, cancelled) }
            if (cancelled()) throw java.io.InterruptedIOException("Import interrupted")
            check(partial.renameTo(destination)) { "Cannot finalize encrypted file" }
            return Prepared(id, destination, key, written.size, written.digest)
        } catch (e: Exception) { partial.delete(); destination.delete(); throw e }
    }
    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 160 && name.none { it.isISOControl() || it == '/' || it == '\\' } && name != "." && name != ".."
}

package com.calcvault.core

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CVCHNK01 | chunkSize:i32 | randomNoncePrefix:8.
 * Records: plaintextLength:i32 | AES-GCM ciphertext+tag. Mandatory authenticated empty final record.
 * A fresh random 256-bit key MUST be used for every file; never re-encrypt using an existing file key.
 * Header, item identity, index and record length are AAD. Readers reject missing/trailing frames.
 */
object ChunkCipher {
    const val CHUNK = 1024 * 1024
    private const val HEADER = 20
    private val magic = "CVCHNK01".toByteArray(Charsets.US_ASCII)
    data class Written(val size: Long, val digest: ByteArray)
    private fun cipher(mode: Int, key: SecretKey, header: ByteArray, id: String, index: Int, length: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(header, 12, 8).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(id.toByteArray(Charsets.UTF_8))
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(length).array())
        }
    }
    fun encrypt(input: InputStream, destination: File, key: SecretKey, id: String, cancelled: () -> Boolean = { false }): Written {
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(magic).putInt(CHUNK).put(prefix).array()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileOutputStream(destination).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.write(header)
            val buffer = ByteArray(CHUNK)
            try {
                var index = 0
                while (true) {
                    if (cancelled()) throw InterruptedIOException("Import interrupted")
                    var length = 0
                    while (length < CHUNK) {
                        if (cancelled()) throw InterruptedIOException("Import interrupted")
                        val n = input.read(buffer, length, CHUNK - length)
                        if (n == -1) break
                        if (n == 0) { val b = input.read(); if (b == -1) break; buffer[length++] = b.toByte() }
                        else length += n
                    }
                    require(index < Int.MAX_VALUE) { "File is too large" }
                    out.writeInt(length)
                    out.write(cipher(Cipher.ENCRYPT_MODE, key, header, id, index++, length).doFinal(buffer, 0, length))
                    if (length == 0) break
                    digest.update(buffer, 0, length)
                    size += length
                }
                out.flush(); file.fd.sync()
            } finally { buffer.fill(0) }
        }
        return Written(size, digest.digest())
    }
    class Reader(file: File, private val key: SecretKey, private val id: String, val size: Long) : Closeable {
        private val source = RandomAccessFile(file, "r")
        private val header = ByteArray(HEADER)
        private var cachedIndex = -1
        private var cached = ByteArray(0)
        private var closed = false
        init {
            try {
                require(size >= 0 && size / CHUNK < Int.MAX_VALUE)
                source.readFully(header)
                require(header.copyOfRange(0, 8).contentEquals(magic) && ByteBuffer.wrap(header, 8, 4).int == CHUNK) { "Invalid encrypted file" }
                val frames = (size + CHUNK - 1) / CHUNK
                val expected = HEADER + size + frames * 20 + 20
                require(source.length() == expected) { "Incomplete encrypted file" }
                source.seek(expected - 20)
                require(source.readInt() == 0)
                val tag = ByteArray(16); source.readFully(tag)
                cipher(Cipher.DECRYPT_MODE, key, header, id, frames.toInt(), 0).doFinal(tag)
            } catch (e: Exception) { source.close(); throw e }
        }
        @Synchronized fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Reader is closed" }
            require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return 0
            if (position >= size) return -1
            val index = (position / CHUNK).toInt()
            if (cachedIndex != index) {
                source.seek(HEADER + index.toLong() * (CHUNK + 20))
                val n = source.readInt()
                require(n == minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt()) { "Invalid chunk length" }
                val encrypted = ByteArray(n + 16); source.readFully(encrypted)
                cached.fill(0)
                cached = cipher(Cipher.DECRYPT_MODE, key, header, id, index, n).doFinal(encrypted)
                cachedIndex = index
            }
            val start = (position % CHUNK).toInt()
            val n = minOf(length, cached.size - start)
            cached.copyInto(target, offset, start, start + n)
            return n
        }
        fun stream(): InputStream = object : InputStream() {
            var position = 0L
            override fun read(): Int { val one = ByteArray(1); return if (read(one) < 0) -1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int = this@Reader.read(position, b, off, len).also { if (it > 0) position += it }
            override fun close() { this@Reader.close() }
        }
        fun verify(expectedDigest: ByteArray) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK)
            try {
                var position = 0L
                while (position < size) { val n = read(position, buffer, 0, buffer.size); digest.update(buffer, 0, n); position += n }
                require(MessageDigest.isEqual(expectedDigest, digest.digest())) { "Import verification failed" }
            } finally { buffer.fill(0) }
        }
        @Synchronized override fun close() { if (!closed) { closed = true; cached.fill(0); source.close() } }
    }
}

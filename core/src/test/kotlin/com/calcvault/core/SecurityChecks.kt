package com.calcvault.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Properties
import java.util.Random
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Synthetic adversarial tests against the production implementation, not a replacement cipher. */
object SecurityChecks {
    private var checks = 0
    private fun scenario(name: String, block: () -> Unit) {
        block(); checks++; println("PASS $name")
    }
    private fun rejected(block: () -> Unit) {
        try { block() }
        catch (_: IOException) { return }
        catch (_: GeneralSecurityException) { return }
        catch (_: IllegalArgumentException) { return }
        error("Tampering unexpectedly accepted")
    }
    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun runAll() {
        checks = 0
        val dir = Files.createTempDirectory("calcvault-security-").toFile()
        try {
            val plain = "SYNTHETIC AUDIT DATA ONLY; NOT USER CONTENT. ".repeat(256).toByteArray()
            val copy = ImportTransaction.prepare(dir, plain.inputStream())
            val wire = copy.file.readBytes()
            val altered = File(dir, "mutated.cv")
            fun reject(bytes: ByteArray, key: SecretKey = copy.key, id: String = copy.id, size: Long = copy.size) {
                altered.writeBytes(bytes)
                rejected { ChunkCipher.Reader(altered, key, id, size).use { it.verify(copy.digest) } }
            }
            scenario("round-trip and ciphertext plaintext-marker sanity check") {
                check(ChunkCipher.Reader(copy.file, copy.key, copy.id, copy.size).stream().use { it.readBytes() }.contentEquals(plain))
                check(!String(wire, Charsets.ISO_8859_1).contains("SYNTHETIC AUDIT DATA"))
            }
            scenario("all 160 single-bit header mutations rejected") {
                repeat(20 * 8) { bit -> reject(wire.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }) }
            }
            scenario("256 seeded ciphertext/data-tag mutations rejected") {
                val random = Random(20261023L)
                repeat(256) {
                    val index = 24 + random.nextInt(wire.size - 20 - 24)
                    reject(wire.copyOf().also { it[index] = (it[index].toInt() xor (1 shl random.nextInt(8))).toByte() })
                }
            }
            scenario("all 128 final authentication-tag bit mutations rejected") {
                repeat(128) { bit -> reject(wire.copyOf().also { val p = wire.size - 16 + bit / 8; it[p] = (it[p].toInt() xor (1 shl (bit % 8))).toByte() }) }
            }
            scenario("64 seeded truncations and appended data rejected") {
                val random = Random(17)
                repeat(64) { reject(wire.copyOf(random.nextInt(wire.size))) }
                reject(wire + byteArrayOf(0)); reject(wire + wire.takeLast(20).toByteArray())
            }
            scenario("invalid frame lengths and false catalog lengths rejected") {
                for (n in listOf(-1, 0, 1, ChunkCipher.CHUNK + 1, Int.MAX_VALUE)) reject(wire.copyOf().also { ByteBuffer.wrap(it, 20, 4).putInt(n) })
                for (n in listOf(-1L, 0L, copy.size - 1, copy.size + 1, Long.MAX_VALUE)) reject(wire, size = n)
            }
            scenario("64 incorrect AES-256 keys and 32 incorrect item identities rejected") {
                repeat(64) { reject(wire, key = freshKey()) }
                repeat(32) { reject(wire, id = UUID.randomUUID().toString()) }
            }
            scenario("modified chunk never populates caller output with unauthenticated bytes") {
                altered.writeBytes(wire.copyOf().also { it[25] = (it[25].toInt() xor 1).toByte() })
                val output = ByteArray(128) { 0x5a }
                rejected { ChunkCipher.Reader(altered, copy.key, copy.id, copy.size).use { it.read(0, output, 0, output.size) } }
                check(output.all { it == 0x5a.toByte() })
            }
            val largePlain = ByteArray(ChunkCipher.CHUNK * 2 + 37) { ((it * 31 + it / 2048) % 251).toByte() }
            val large = ImportTransaction.prepare(dir, largePlain.inputStream())
            val largeWire = large.file.readBytes()
            val frame = ChunkCipher.CHUNK + 20
            fun rejectLarge(bytes: ByteArray) {
                altered.writeBytes(bytes)
                rejected { ChunkCipher.Reader(altered, large.key, large.id, large.size).use { it.verify(large.digest) } }
            }
            scenario("full-size chunk reordering and duplication rejected") {
                val swapped = largeWire.copyOf()
                largeWire.copyInto(swapped, 20, 20 + frame, 20 + frame * 2)
                largeWire.copyInto(swapped, 20 + frame, 20, 20 + frame)
                rejectLarge(swapped)
                rejectLarge(largeWire.copyOf().also { largeWire.copyInto(it, 20 + frame, 20, 20 + frame) })
            }
            scenario("cross-file chunk transplant rejected") {
                val other = ImportTransaction.prepare(dir, largePlain.inputStream())
                rejectLarge(largeWire.copyOf().also { other.file.readBytes().copyInto(it, 20, 20, 20 + frame) })
            }
            scenario("documented lazy authentication: intact prefix readable, corrupted later chunk rejected") {
                altered.writeBytes(largeWire.copyOf().also { val p = 20 + frame + 9; it[p] = (it[p].toInt() xor 1).toByte() })
                ChunkCipher.Reader(altered, large.key, large.id, large.size).use { reader ->
                    val prefix = ByteArray(32)
                    check(reader.read(0, prefix, 0, prefix.size) == prefix.size)
                    check(prefix.contentEquals(largePlain.copyOf(32)))
                    rejected { reader.read(ChunkCipher.CHUNK.toLong(), ByteArray(32), 0, 32) }
                }
            }
            scenario("cached authentic bytes remain correct after a different chunk fails authentication") {
                altered.writeBytes(largeWire.copyOf().also { val p = 20 + frame + 9; it[p] = (it[p].toInt() xor 1).toByte() })
                ChunkCipher.Reader(altered, large.key, large.id, large.size).use { reader ->
                    val prefix = ByteArray(32)
                    check(reader.read(0, prefix, 0, prefix.size) == prefix.size)
                    rejected { reader.read(ChunkCipher.CHUNK.toLong(), ByteArray(32), 0, 32) }
                    prefix.fill(0x7f)
                    check(reader.read(0, prefix, 0, prefix.size) == prefix.size)
                    check(prefix.contentEquals(largePlain.copyOf(32))) { "Authentication failure left a zeroed cache marked valid" }
                }
            }
            scenario("128 repeated imports have distinct sampled AES-256 keys, IDs and ciphertext") {
                val keys = mutableSetOf<String>(); val ids = mutableSetOf<String>(); val hashes = mutableSetOf<String>()
                repeat(128) {
                    val p = ImportTransaction.prepare(dir, plain.inputStream())
                    check(p.key.encoded.size == 32)
                    check(keys.add(hex(p.key.encoded))); check(ids.add(p.id))
                    check(hashes.add(hex(MessageDigest.getInstance("SHA-256").digest(p.file.readBytes()))))
                    p.file.delete()
                }
            }
            scenario("ciphertext length reveals unpadded plaintext size relationship") {
                check(large.file.length() == 20L + large.size + ((large.size + ChunkCipher.CHUNK - 1) / ChunkCipher.CHUNK) * 20 + 20)
            }
            scenario("app cooldown simulation and forward-clock limitation") {
                var now = 0L; var policy = AttemptPolicy(); var attempts = 0
                while (now < 86_400_000L) { policy = policy.reserve(now); attempts++; now = policy.deadline }
                check(attempts < 400)
                check(policy.remaining(policy.recordedAt) > 0)
                check(policy.remaining(policy.deadline + 1) == 0L)
                println("SIMULATION virtual_24h_allowed_attempts=$attempts; forward jump to deadline permits another attempt; not a hardware rate limiter")
            }
            scenario("bounded offline guessing succeeds IF synthetic verifier and salt are already extracted") {
                val pin = "000063".toCharArray() // Deliberately small, synthetic search; NOT a full PIN-space attack.
                val verifier = PinVerifier.create(pin)
                repeat(4) { verifier.matches("999999".toCharArray()) }
                var found = false; var attempts = 0
                val start = System.nanoTime()
                for (n in 0..63) {
                    val guess = n.toString().padStart(6, '0').toCharArray()
                    attempts++
                    try { if (verifier.matches(guess)) { found = true; break } } finally { guess.fill('\u0000') }
                }
                val seconds = (System.nanoTime() - start) / 1e9
                check(found && attempts == 64)
                println("BENCHMARK synthetic_extracted_verifier_guesses=$attempts seconds=$seconds guesses_per_second=${attempts / seconds}; conditional single-process CPU sample, NOT device/GPU cracking time")
                pin.fill('\u0000')
            }
        } finally { dir.deleteRecursively() }
        println("$checks security scenarios passed; expected limitations were reproduced, not repaired")
    }

    private fun writeInterop(directory: File) {
        directory.mkdirs()
        val plain = ByteArray(ChunkCipher.CHUNK * 2 + 37) { ((it * 31 + it / 2048) % 251).toByte() }
        // Public deterministic fixture key ONLY; never use this key with real data.
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val id = UUID.randomUUID().toString()
        val result = ChunkCipher.encrypt(plain.inputStream(), File(directory, "jvm.cv"), key, id)
        File(directory, "plain.bin").writeBytes(plain)
        Properties().apply {
            setProperty("key", hex(key.encoded)); setProperty("id", id)
            setProperty("size", result.size.toString()); setProperty("digest", hex(result.digest))
        }.also { p -> File(directory, "jvm.properties").outputStream().use { p.store(it, "SYNTHETIC PUBLIC TEST FIXTURE; NO USER DATA OR KEYS") } }
    }
    private fun verifyPython(directory: File) {
        val p = Properties().apply { File(directory, "python.properties").inputStream().use { load(it) } }
        ChunkCipher.Reader(File(directory, "python.cv"), SecretKeySpec(unhex(p.getProperty("key")), "AES"), p.getProperty("id"), p.getProperty("size").toLong()).use { reader ->
            reader.verify(unhex(p.getProperty("digest")))
            check(reader.stream().readBytes().contentEquals(File(directory, "plain.bin").readBytes()))
        }
        println("PASS production JVM reader accepts independently generated Python/OpenSSL fixture with exact plaintext")
    }
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--verify-python") verifyPython(File(args[1]))
        else { runAll(); if (args.isNotEmpty()) writeInterop(File(args[0])) }
    }
}

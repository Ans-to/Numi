package com.calcvault.core

import java.io.*
import java.nio.file.Files
import javax.crypto.KeyGenerator

/** Shared by the Gradle/JUnit suite and the offline Kotlin runner. No Android substitutes or mocks. */
object CoreChecks {
    private var count = 0
    private fun checkCase(name: String, block: () -> Unit) { block(); count++; println("PASS $name") }
    private fun fails(block: () -> Unit) { check(runCatching(block).isFailure) { "Expected rejection" } }
    private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected; got $actual" } }
    fun runAll() {
        count = 0
        checkCase("addition/subtraction/multiplication/division") {
            mapOf("1+2" to "3", "8-10" to "-2", "7*8" to "56", "10/4" to "2.5").forEach { (a,b) -> equal(b, Calculator.evaluate(a)) }
        }
        checkCase("decimal arithmetic has no binary float artifacts") { equal("0.3", Calculator.evaluate("0.1+0.2")); equal("1", Calculator.evaluate("0.1*10")) }
        checkCase("precedence and parentheses") { equal("14", Calculator.evaluate("2+3*4")); equal("20", Calculator.evaluate("(2+3)*4")) }
        checkCase("negative numbers and unary signs") { equal("6", Calculator.evaluate("-2*-3")); equal("-5", Calculator.evaluate("-(2+3)")) }
        checkCase("postfix percentages") { equal("10", Calculator.evaluate("200*5%")); equal("0.5", Calculator.evaluate("50%")) }
        checkCase("Unicode calculator operators") { equal("6", Calculator.evaluate("9÷3×2")); equal("-3", Calculator.evaluate("2−5")) }
        checkCase("leading zeros and decimal-only numbers") { equal("12.5", Calculator.evaluate("00012+.5")) }
        checkCase("division by zero is rejected") { listOf("1/0", "0/0", "3/(1-1)").forEach { fails { Calculator.evaluate(it) } } }
        checkCase("malformed expressions never accepted") { listOf("", ".", "1..2", "1+", "()", "(2", "2)", "2a", "1**2", "2 2").forEach { fails { Calculator.evaluate(it) } } }
        checkCase("parser depth and size bounded") { fails { Calculator.evaluate("(".repeat(40)+"1"+")".repeat(40)) }; fails { Calculator.evaluate("9".repeat(257)) } }
        checkCase("finite rounded repeated division") { check(Calculator.evaluate("1/3").startsWith("0.333333")); equal("0", Calculator.evaluate("-0")) }
        val pin = "001237".toCharArray()
        val verifier = PinVerifier.create(pin)
        checkCase("correct PIN accepted including leading zeros") { check(verifier.matches(pin)) }
        checkCase("wrong PIN rejected") { check(!verifier.matches("001238".toCharArray())) }
        checkCase("arithmetic expression cannot be a PIN candidate") { check(!PinVerifier.isCandidate("001237+0".toCharArray())); check(!verifier.matches("001237+0".toCharArray())) }
        checkCase("short, long, Unicode and signed PINs rejected") { listOf("12345", "1".repeat(13), "１２３４５６", "-123456").forEach { fails { PinVerifier.create(it.toCharArray()) } } }
        checkCase("PIN uses random salts and distinct verifiers") { val other = PinVerifier.create(pin); check(!verifier.salt.contentEquals(other.salt)); check(!verifier.hash.contentEquals(other.hash)); check(!verifier.hash.contentEquals(String(pin).toByteArray())) }
        checkCase("persistent progressive attempt deadlines") {
            var p = AttemptPolicy(); var now = 1000L
            repeat(12) { p = p.reserve(now); check(p.remaining(now) >= 1000); now = p.deadline }
            equal(300000L, p.deadline-p.recordedAt); equal(300000L, p.remaining(p.recordedAt - 1)); equal(0L, p.remaining(p.deadline))
        }
        checkCase("immediate background lock") { val s = SessionClock(); s.unlock(); s.background(100, 0); check(!s.unlocked) }
        checkCase("grace timeout respected and expired on foreground") { val s = SessionClock(); s.unlock(); s.background(100, 15000); s.foreground(1000, 15000); check(s.unlocked); s.background(2000, 15000); s.foreground(17000, 15000); check(!s.unlocked) }
        checkCase("new process always starts locked") { check(!SessionClock().unlocked) }
        checkCase("monotonic clock rollback locks session") { val s = SessionClock(); s.unlock(); s.background(100, 1000); s.foreground(90, 1000); check(!s.unlocked) }
        val dir = Files.createTempDirectory("calcvault-tests").toFile()
        try {
            val original = "PRIVATE CONTENT THAT MUST NOT APPEAR AS PLAINTEXT".repeat(200).toByteArray()
            val prepared = ImportTransaction.prepare(dir, ByteArrayInputStream(original))
            checkCase("encrypted file does not contain plaintext") { check(!String(prepared.file.readBytes(), Charsets.ISO_8859_1).contains("PRIVATE CONTENT")) }
            checkCase("import decrypts exactly to original") { ChunkCipher.Reader(prepared.file, prepared.key, prepared.id, prepared.size).stream().use { check(it.readBytes().contentEquals(original)) } }
            checkCase("wrong key rejected") { val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); fails { ChunkCipher.Reader(prepared.file, key, prepared.id, prepared.size).close() } }
            checkCase("wrong item identity rejected") { fails { ChunkCipher.Reader(prepared.file, prepared.key, "other-id", prepared.size).close() } }
            checkCase("modified ciphertext rejected before returning chunk plaintext") {
                val bad = File(dir,"modified"); bad.writeBytes(prepared.file.readBytes().also { it[30] = (it[30].toInt() xor 1).toByte() })
                fails { ChunkCipher.Reader(bad, prepared.key, prepared.id, prepared.size).use { it.read(0, ByteArray(100), 0, 100) } }
            }
            checkCase("modified header rejected") {
                val bad = File(dir,"header"); bad.writeBytes(prepared.file.readBytes().also { it[12] = (it[12].toInt() xor 1).toByte() })
                fails { ChunkCipher.Reader(bad, prepared.key, prepared.id, prepared.size).close() }
            }
            checkCase("truncated file and trailing bytes rejected") {
                for (delta in listOf(-1, -20, 1)) {
                    val bad = File(dir,"length"); bad.writeBytes(prepared.file.readBytes().copyOf(prepared.file.length().toInt()+delta))
                    fails { ChunkCipher.Reader(bad, prepared.key, prepared.id, prepared.size).close() }
                }
            }
            checkCase("empty import is authenticated") { val empty = ImportTransaction.prepare(dir, ByteArrayInputStream(byteArrayOf())); ChunkCipher.Reader(empty.file, empty.key, empty.id, 0).use { equal(-1, it.read(0, ByteArray(1), 0, 1)) } }
            checkCase("large multi-chunk import, digest and random-access reads") {
                val bytes = ByteArray(ChunkCipher.CHUNK * 12 + 41) { ((it * 31 + it / 7) % 251).toByte() }
                val large = ImportTransaction.prepare(dir, ByteArrayInputStream(bytes))
                ChunkCipher.Reader(large.file, large.key, large.id, large.size).use { r ->
                    r.verify(large.digest)
                    for (position in listOf(0, 17, ChunkCipher.CHUNK-3, ChunkCipher.CHUNK, bytes.size-2)) {
                        val out = ByteArray(23); val n = r.read(position.toLong(), out, 0, out.size)
                        check(out.copyOf(n).contentEquals(bytes.copyOfRange(position, position+n)))
                    }
                }
            }
            checkCase("256 MiB generated import stays streaming and verifies without a full-file buffer") {
                val stream = object : InputStream() {
                    var remaining = 256L * 1024 * 1024
                    override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 90 }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (remaining == 0L) return -1
                        val n = minOf(remaining, length.toLong()).toInt()
                        bytes.fill(90, offset, offset + n); remaining -= n; return n
                    }
                }
                val large = ImportTransaction.prepare(dir, stream)
                equal(256L * 1024 * 1024, large.size)
                ChunkCipher.Reader(large.file, large.key, large.id, large.size).use { it.verify(large.digest) }
                check(large.file.delete())
            }
            checkCase("verification obeys cancellation without publishing data") {
                ChunkCipher.Reader(prepared.file, prepared.key, prepared.id, prepared.size).use { r -> fails { r.verify(prepared.digest) { true } } }
            }
            checkCase("exact chunk boundary requires final authenticated record") { val p = ImportTransaction.prepare(dir, ByteArrayInputStream(ByteArray(ChunkCipher.CHUNK))); ChunkCipher.Reader(p.file, p.key, p.id, p.size).use { it.verify(p.digest) } }
            checkCase("interrupted provider leaves no partial or valid item") {
                val before = dir.list()!!.toSet()
                fails { ImportTransaction.prepare(dir, object : InputStream() { var read = 0; override fun read(): Int { if (read++ > 1500) throw IOException("Provider lost"); return 42 } }) }
                equal(before, dir.list()!!.toSet())
            }
            checkCase("cancelled import leaves no partial file") {
                val before = dir.list()!!.toSet()
                fails { ImportTransaction.prepare(dir, ByteArrayInputStream(original)) { true } }
                equal(before, dir.list()!!.toSet())
            }
            checkCase("no unencrypted import artifacts") { check(dir.list()!!.none { it.endsWith("partial") }); check(dir.listFiles()!!.filter { it.extension == "cv" }.all { String(it.readBytes(), Charsets.ISO_8859_1).indexOf("PRIVATE CONTENT") == -1 }) }
            checkCase("reopened reader works after simulated process restart") { ChunkCipher.Reader(prepared.file, prepared.key, prepared.id, prepared.size).use { it.verify(prepared.digest) } }
            checkCase("closed reader refuses access") { val r = ChunkCipher.Reader(prepared.file, prepared.key, prepared.id, prepared.size); r.close(); fails { r.read(0, ByteArray(1), 0, 1) } }
            checkCase("invalid filenames rejected") { listOf("", " ", ".", "..", "a/b", "a\\b", "x\u0000z", "x".repeat(161)).forEach { check(!ImportTransaction.validName(it)) }; check(ImportTransaction.validName("Family trip 2026.jpg")) }
        } finally { dir.deleteRecursively(); pin.fill('\u0000') }
        println("$count core checks passed")
    }
    @JvmStatic fun main(args: Array<String>) = runAll()
}

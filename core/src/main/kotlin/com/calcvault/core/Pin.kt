package com.calcvault.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinVerifier(val salt: ByteArray, val hash: ByteArray, val iterations: Int = 600_000) {
    init { require(salt.size == 32 && hash.size == 32 && iterations in 100_000..2_000_000) }
    fun matches(pin: CharArray): Boolean {
        if (!isCandidate(pin)) return false
        val actual = derive(pin, salt, iterations)
        return try { MessageDigest.isEqual(hash, actual) } finally { actual.fill(0) }
    }
    companion object {
        fun isCandidate(pin: CharArray) = pin.size in 6..12 && pin.all { it in '0'..'9' }
        fun create(pin: CharArray): PinVerifier {
            require(isCandidate(pin)) { "Use 6–12 digits" }
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return PinVerifier(salt, derive(pin, salt, 600_000))
        }
        private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin, salt, iterations, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
    }
}

/** Deadline is persisted BEFORE password verification. Rolling the clock back cannot bypass it. */
data class AttemptPolicy(val failures: Int = 0, val deadline: Long = 0, val recordedAt: Long = 0) {
    fun remaining(now: Long): Long = when {
        deadline == 0L -> 0L
        now < recordedAt -> 300_000L
        else -> (deadline - now).coerceAtLeast(0)
    }
    fun reserve(now: Long): AttemptPolicy {
        val next = (failures + 1).coerceAtMost(20)
        val delay = if (next < 5) 1_000L else (5_000L shl (next - 5).coerceAtMost(6)).coerceAtMost(300_000)
        return AttemptPolicy(next, now + delay, now)
    }
}

class SessionClock {
    var unlocked = false; private set
    private var backgroundAt: Long? = null
    fun unlock() { unlocked = true; backgroundAt = null }
    fun lock() { unlocked = false; backgroundAt = null }
    fun background(now: Long, timeout: Long) { backgroundAt = now; if (timeout == 0L) lock() }
    fun foreground(now: Long, timeout: Long) {
        backgroundAt?.let { if (now < it || now - it >= timeout) lock() }
        backgroundAt = null
    }
}

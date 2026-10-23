package com.calcvault.data

import android.util.Base64
import com.calcvault.core.AttemptPolicy
import com.calcvault.core.PinVerifier
import org.json.JSONObject

internal fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
internal fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

data class Preferences(val light: Boolean = false, val lockSeconds: Int = 0, val biometric: Boolean = false)
sealed interface UnlockResult {
    data object Open : UnlockResult
    data object Calculate : UnlockResult
    data class Delayed(val seconds: Long) : UnlockResult
}
class AuthRepository(private val store: SecretStore) {
    private var record = store.read("auth")?.let { data -> try { JSONObject(String(data, Charsets.UTF_8)) } finally { data.fill(0) } }
    val configured get() = record != null
    val preferences get() = record?.let { Preferences(it.optBoolean("light"), it.optInt("timeout"), it.optBoolean("biometric")) } ?: Preferences()
    private fun save(next: JSONObject) {
        val data = next.toString().toByteArray()
        try { store.write("auth", data); record = next } finally { data.fill(0) }
    }
    @Synchronized fun setup(pin: CharArray) { check(record == null); save(withPin(JSONObject(), pin)) }
    private fun withPin(json: JSONObject, pin: CharArray): JSONObject {
        val verifier = PinVerifier.create(pin)
        return json.put("salt", verifier.salt.b64()).put("hash", verifier.hash.b64()).put("iterations", verifier.iterations)
            .put("failures", 0).put("deadline", 0).put("recordedAt", 0)
    }
    @Synchronized fun changePin(pin: CharArray) { save(withPin(JSONObject(checkNotNull(record).toString()), pin)) }
    @Synchronized fun attempt(pin: CharArray, now: Long = System.currentTimeMillis()): UnlockResult {
        if (!PinVerifier.isCandidate(pin)) return UnlockResult.Calculate
        val r = checkNotNull(record)
        val policy = AttemptPolicy(r.optInt("failures"), r.optLong("deadline"), r.optLong("recordedAt"))
        val remaining = policy.remaining(now)
        if (remaining > 0) return UnlockResult.Delayed((remaining + 999) / 1000)
        val reserved = policy.reserve(now)
        save(JSONObject(r.toString()).put("failures", reserved.failures).put("deadline", reserved.deadline).put("recordedAt", reserved.recordedAt))
        val correct = PinVerifier(r.getString("salt").unbase64(), r.getString("hash").unbase64(), r.getInt("iterations")).matches(pin)
        if (correct) { resetAttempts(); return UnlockResult.Open }
        return UnlockResult.Calculate
    }
    @Synchronized fun resetAttempts() {
        save(JSONObject(checkNotNull(record).toString()).put("failures", 0).put("deadline", 0).put("recordedAt", 0))
    }
    @Synchronized fun settings(p: Preferences) {
        require(p.lockSeconds in listOf(0, 15, 60))
        save(JSONObject(checkNotNull(record).toString()).put("light", p.light).put("timeout", p.lockSeconds).put("biometric", p.biometric))
    }
    @Synchronized fun setBiometric(iv: ByteArray?, ciphertext: ByteArray?) {
        save(JSONObject(checkNotNull(record).toString()).put("biometric", ciphertext != null)
            .put("bioIv", iv?.b64()).put("bioCipher", ciphertext?.b64()))
    }
    fun biometricRecord(): Pair<ByteArray, ByteArray> {
        check(preferences.biometric)
        return checkNotNull(record).let { it.getString("bioIv").unbase64() to it.getString("bioCipher").unbase64() }
    }
}

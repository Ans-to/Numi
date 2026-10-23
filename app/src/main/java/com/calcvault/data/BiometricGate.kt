package com.calcvault.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BiometricGate(private val activity: FragmentActivity) {
    private val alias = "calcvault.biometric.v1"
    private val marker = "Calc Vault biometric approval v1".toByteArray()
    fun available() = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun request(enroll: Boolean, record: Pair<ByteArray, ByteArray>?, complete: (ByteArray?, ByteArray?) -> Unit, error: (String) -> Unit) {
        try {
            check(available())
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (enroll) {
                ks.deleteEntry(alias)
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1) }
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
            }
            val key = ks.getKey(alias, null) as? SecretKey ?: throw IllegalStateException("Biometric key missing")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (enroll) cipher.init(Cipher.ENCRYPT_MODE, key)
            else cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, checkNotNull(record).first))
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val approved = checkNotNull(result.cryptoObject?.cipher)
                        approved.updateAAD(marker)
                        if (enroll) complete(approved.iv, approved.doFinal(marker))
                        else { check(approved.doFinal(checkNotNull(record).second).contentEquals(marker)); complete(null, null) }
                    } catch (_: Exception) { error("Biometric key is unavailable or changed. Use your PIN and enable biometrics again.") }
                }
                override fun onAuthenticationError(code: Int, text: CharSequence) { error("Biometric authentication was not completed. You can use your PIN.") }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(if (enroll) "Enable biometric unlock" else "Unlock private storage")
                .setSubtitle("Your calculator PIN is always available")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN").build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { error("Strong biometrics are unavailable or enrollment changed. Use your PIN.") }
    }
}

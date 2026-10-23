package com.calcvault

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calcvault.data.*
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable-emulator tests. These explicitly demonstrate limits, NOT unprivileged exploits. */
@RunWith(AndroidJUnit4::class)
class SecurityBoundaryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun configuredAuth(): AuthRepository = AuthRepository(SecretStore(context)).also {
        if (!it.configured) it.setup("381729".toCharArray()) // same disposable fixture as UI tests
        it.resetAttempts()
    }

    @Test fun inProcessCodeCanReadVaultWithoutKnowingThePin() {
        val auth = configuredAuth()
        assertTrue(auth.attempt("999999".toCharArray()) is UnlockResult.Calculate)
        val source = File(context.cacheDir, "audit-in-process.txt").apply { writeText("SYNTHETIC IN-PROCESS SECURITY BOUNDARY FIXTURE") }
        val repository = VaultRepository(context, SecretStore(context))
        var id: String? = null
        try {
            // No successful PIN attempt or biometric approval precedes this call.
            repository.unlock()
            val copy = repository.importWithReceipt(Uri.fromFile(source), null)
            id = copy.item.id
            repository.lock()
            repository.unlock()
            val item = repository.snapshot().items.single { it.id == id }
            assertArrayEquals(source.readBytes(), repository.reader(item).stream().use { it.readBytes() })
            assertNotNull(SecretStore(context).read("catalog"))
        } finally {
            if (repository.unlocked) id?.let { repository.delete(setOf(it)) }
            repository.lock(); source.delete(); auth.resetAttempts()
        }
    }

    @Test fun rootKeyIsNotExportableButDoesNotRequireUserAuthentication() {
        configuredAuth()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("calcvault.root.v1", null) as SecretKey
        assertNull("Public Android API must not export root-key bytes", key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertFalse("Documents current PIN/biometric app-gate limitation", info.isUserAuthenticationRequired)
    }

    @Test fun cooldownSurvivesReloadButPrivilegedAuthFileReplayBypassesIt() {
        val auth = configuredAuth()
        val record = File(context.noBackupFilesDir, "security/auth")
        val snapshot = record.readBytes()
        val now = 1_000_000L // explicit synthetic time, no device clock change
        try {
            assertTrue(auth.attempt("999999".toCharArray(), now) is UnlockResult.Calculate)
            assertTrue(AuthRepository(SecretStore(context)).attempt("999999".toCharArray(), now) is UnlockResult.Delayed)
            // Deliberate privileged/app-UID write, unavailable to ordinary sandboxed apps.
            record.writeBytes(snapshot)
            assertTrue(AuthRepository(SecretStore(context)).attempt("999999".toCharArray(), now) is UnlockResult.Calculate)
        } finally { record.writeBytes(snapshot); snapshot.fill(0) }
    }

    @Test fun encryptedRecordsRejectBitTamperingAndCrossRecordSubstitution() {
        val store = SecretStore(context)
        val a = File(context.noBackupFilesDir, "security/audit_record_a")
        val b = File(context.noBackupFilesDir, "security/audit_record_b")
        val plain = "SYNTHETIC AUTHENTICATED RECORD".toByteArray()
        try {
            store.write("audit_record_a", plain)
            val first = a.readBytes()
            store.write("audit_record_a", plain)
            assertFalse(first.contentEquals(a.readBytes()))
            assertArrayEquals(plain, store.read("audit_record_a"))
            b.writeBytes(a.readBytes())
            assertTrue(runCatching { store.read("audit_record_b") }.isFailure)
            a.writeBytes(a.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertTrue(runCatching { store.read("audit_record_a") }.isFailure)
        } finally {
            listOf(a, b).forEach { f -> f.delete(); File(f.path + ".bak").delete(); File(f.path + ".new").delete() }
            plain.fill(0)
        }
    }
}

package com.calcvault

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calcvault.data.AuthRepository
import com.calcvault.data.SecretStore
import com.calcvault.data.UnlockResult
import com.calcvault.data.VaultRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {
    @Test fun realKeystoreCatalogImportLockDeleteAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This test runs against the disposable emulator's app storage only.
        context.noBackupFilesDir.deleteRecursively()
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("calcvault.root.v1")
        val secrets = SecretStore(context)
        val auth = AuthRepository(secrets)
        val pin = "381729".toCharArray()
        auth.setup(pin)
        assertTrue(auth.attempt("381728".toCharArray()) is UnlockResult.Calculate)
        auth.resetAttempts()
        assertTrue(auth.attempt("381729+0".toCharArray()) is UnlockResult.Calculate)
        assertTrue(auth.attempt(pin) is UnlockResult.Open)
        assertTrue(AuthRepository(SecretStore(context)).attempt(pin) is UnlockResult.Open)
        val repo = VaultRepository(context, secrets)
        repo.unlock()
        assertTrue("An empty index is committed before the first import", secrets.exists("catalog"))
        // file:// is confined to this test's private fixture; production import uses system content URIs.
        val source = File(context.cacheDir, "device-test-source.txt")
        val bytes = ByteArray(2_100_031) { (it % 251).toByte() }
        source.writeBytes(bytes)
        try {
            assertTrue(repo.import(Uri.fromFile(source), null))
            assertFalse(repo.import(Uri.fromFile(source), null))
            val item = repo.snapshot().items.single()
            assertArrayEquals(bytes, repo.reader(item).stream().use { it.readBytes() })
            repo.createFolder("Private documents")
            repo.rename(item.id, "Renamed.txt")
            repo.move(setOf(item.id), repo.snapshot().folders.single().id)
            val reader = repo.reader(item)
            repo.lock()
            assertTrue(runCatching { reader.read(0, ByteArray(10), 0, 10) }.isFailure)
            val reopened = VaultRepository(context, SecretStore(context))
            assertEquals("Renamed.txt", reopened.unlock().items.single().name)
            assertArrayEquals(bytes, reopened.reader(reopened.snapshot().items.single()).stream().use { it.readBytes() })
            val second = File(context.cacheDir, "second-test.txt").apply { writeText("Second item") }
            try { assertTrue(reopened.import(Uri.fromFile(second), null)) } finally { second.delete() }
            reopened.delete(setOf(item.id))
            assertEquals(1, reopened.snapshot().items.size)
            assertFalse(File(context.noBackupFilesDir, "vault/${item.id}.cv").exists())
            assertTrue(File(context.noBackupFilesDir, "vault").listFiles()!!.all { it.extension == "cv" })
            assertTrue(source.exists())
            reopened.lock()
        } finally { source.delete(); pin.fill('\u0000') }
    }
    @Test fun missingSourceHasActionableErrorAndDoesNotPublishAnItem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretStore(context)
        val auth = AuthRepository(secrets)
        if (!auth.configured) auth.setup("381729".toCharArray())
        val repo = VaultRepository(context, secrets)
        val before = repo.unlock().items.map { it.id }
        val missing = Uri.fromFile(File(context.cacheDir, "missing-source-${System.nanoTime()}.png"))
        val error = runCatching { repo.import(missing, null) }.exceptionOrNull()
        assertTrue(error is com.calcvault.data.VaultImportException)
        assertEquals(com.calcvault.data.ImportProblem.UNAVAILABLE, (error as com.calcvault.data.VaultImportException).problem)
        assertFalse(error.message.orEmpty().contains(context.cacheDir.path))
        assertEquals(before, repo.snapshot().items.map { it.id })
        repo.lock()
    }
    @Test fun noNetworkOrStoragePermissionsAndBackupDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.WAKE_LOCK"))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val activity = context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, activity.launchMode)
    }
}

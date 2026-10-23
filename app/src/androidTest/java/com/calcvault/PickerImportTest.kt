package com.calcvault

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.calcvault.media.PrivateMedia
import com.calcvault.ui.VaultViewModel
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Real Android picker transitions, not file:// repository calls or mocked activity results. */
class PickerImportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val model get() = ViewModelProvider(compose.activity)[VaultViewModel::class.java]
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun dismissLauncherAnr(): Boolean {
        // A cold CI emulator can show a launcher ANR over unrelated apps. Never
        // dismiss Calculator or picker failures: those must fail this regression.
        if (!device.hasObject(By.text("Pixel Launcher isn't responding"))) return false
        device.findObject(By.text("Close app"))?.click()
        return true
    }
    @Before fun prepareSystemUi() {
        device.registerWatcher("launcherAnr") { dismissLauncherAnr() }
        dismissLauncherAnr()
        shell("am force-stop com.google.android.apps.nexuslauncher")
    }
    @After fun removeSystemWatcher() { device.removeWatcher("launcherAnr") }
    private fun awaitSystemPicker(label: String) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            val dismissed = dismissLauncherAnr()
            if (dismissed) device.findObject(By.pkg("com.calcvault").text(label))?.click()
            if (device.wait(Until.hasObject(By.pkg(Pattern.compile(".*(documentsui|media.module).*"))), 1000)) return
        }
        fail("System picker must actually be foreground")
    }
    private fun type(pin: String) { pin.forEach { compose.onNodeWithText(it.toString(), useUnmergedTree = true).performClick() } }
    private fun unlock() {
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy }
        if (!model.state.value.configured) {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Set up private storage").performClick()
            compose.onNodeWithText("Continue").performClick()
            type("381729"); compose.onNodeWithText("=").performClick()
            type("381729"); compose.onNodeWithText("=").performClick()
            compose.waitUntil(15000) { model.state.value.configured && !model.state.value.busy }
            compose.onNodeWithText("OK").performClick()
        }
        model.auth.resetAttempts()
        compose.onNodeWithText("AC").performClick()
        type("381729"); compose.onNodeWithText("=").performClick()
        compose.waitUntil(20000) { model.state.value.unlocked }
        compose.runOnUiThread { model.message(null) }
    }
    private fun runPickerCase(photoPicker: Boolean) {
        unlock()
        val name = "CalcVault-picker-${System.nanoTime()}.png"
        val path = "/sdcard/Download/$name"
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(if (photoPicker) Color.BLUE else Color.RED) }
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        // Shell creates the source, not the app. Reading the returned document relies on
        // the system picker's content URI grant. This is synthetic data on a disposable emulator.
        shell("mkdir -p /sdcard/Download; printf '%s' '${Base64.encodeToString(bytes, Base64.NO_WRAP)}' | base64 -d > '$path'; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$path'")
        try {
            dismissLauncherAnr()
            compose.onNodeWithText("Import files", useUnmergedTree = true).performClick()
            compose.onNodeWithText(if (photoPicker) "Photos & videos" else "Documents & other files", useUnmergedTree = true).performClick()
            awaitSystemPicker(if (photoPicker) "Photos & videos" else "Documents & other files")
            // The regression: pausing/locking the app used to unregister the Compose launcher.
            assertFalse("Immediate background locking remains enabled", model.state.value.unlocked)
            if (!photoPicker) {
                var row = device.wait(Until.findObject(By.text(name)), 5000)
                if (row == null) {
                    device.findObject(By.desc("Show roots"))?.click()
                    device.wait(Until.findObject(By.text("Downloads")), 10000)?.click()
                    row = device.wait(Until.findObject(By.text(name)), 20000)
                }
                assertNotNull("Fixture should appear in Android DocumentsUI Downloads", row)
                row!!.click()
                if (!device.wait(Until.hasObject(By.pkg("com.calcvault")), 3000)) {
                    device.findObject(By.text(Pattern.compile("(?i)(select|open|done)")))?.click()
                }
            } else {
                // Only synthetic images exist in this clean test emulator.
                val image = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/icon_thumbnail"))), 10000)
                    ?: device.wait(Until.findObject(By.desc(Pattern.compile("(?i)Photo taken.*"))), 10000)
                assertNotNull("A real photo picker thumbnail should be visible", image)
                image!!.click()
                val add = device.wait(Until.findObject(By.text(Pattern.compile("(?i)Add.*"))), 10000)
                assertNotNull("Photo picker should offer its Add confirmation", add)
                add!!.click()
            }
            assertTrue(device.wait(Until.hasObject(By.pkg("com.calcvault")), 15000))
            compose.waitUntil(15000) { model.state.value.pendingImports == 1 }
            assertFalse(model.state.value.unlocked)
            // Result survives lock and activity recreation; callbacks remain Activity-owned.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { !model.state.value.concealed }
            unlock()
            compose.waitUntil(30000) { !model.state.value.busy && model.state.value.catalog.items.any { it.name == name } }
            val item = model.state.value.catalog.items.first { it.name == name }
            assertEquals("Photos", item.category)
            assertArrayEquals(bytes, model.vault.reader(item).stream().use { it.readBytes() })
            val decoded = PrivateMedia.image(model.vault, item, 256)
            assertEquals(if (photoPicker) Color.BLUE else Color.RED, decoded.getPixel(0, 0))
            decoded.recycle()
            compose.runOnUiThread { model.message(null) }
            compose.onNodeWithText(name, useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(name).assertIsDisplayed()
            // Original remains intact; import never removes it.
            assertTrue(shell("test -f '$path' && echo present").contains("present"))
            assertEquals(0, model.state.value.pendingImports)
            device.pressBack()
            compose.onNodeWithContentDescription("Lock vault").performClick()
        } catch (e: Throwable) {
            val hierarchy = ByteArrayOutputStream()
            runCatching { device.dumpWindowHierarchy(hierarchy) }
            val nodes = Regex("<node [^>]+>").findAll(hierarchy.toString())
                .map { it.value }.filter { !it.contains("package=\"com.android.systemui\"") }
                .map { node -> Regex("(?:text|resource-id|package|content-desc|bounds)=\"[^\"]+\"").findAll(node).joinToString(" ") { it.value } }
                .filter { it.contains("text=") || it.contains("content-desc=") || it.contains("resource-id=") }
                .joinToString("\n").take(7000)
            throw AssertionError("${e.message}\nSynthetic test picker UI: $nodes", e)
        } finally {
            shell("rm -f '$path'; content delete --uri content://media/external/images/media --where \"_display_name='$name'\"")
        }
    }
    @Test fun documentsPickerReturnsAfterLockAndRecreationAndImportsVerifiedPhoto() = runPickerCase(false)
    @Test fun photoPickerReturnsAfterLockAndRecreationAndImportsVerifiedPhoto() = runPickerCase(true)
}

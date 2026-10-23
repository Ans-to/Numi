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
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.Configurator
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
@SdkSuppress(minSdkVersion = 31)
class PickerImportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val model get() = ViewModelProvider(compose.activity)[VaultViewModel::class.java]
    private fun shell(command: String): String {
        // UiAutomation does not interpret shell pipelines itself. Feed a real shell
        // over its stdin pipe; otherwise fixture creation can silently do nothing.
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use { it.write("exec 2>&1\nset -e\n$command\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
    }
    private fun dismissLauncherAnr(): Boolean {
        // A cold CI emulator can show a launcher ANR over unrelated apps. Never
        // dismiss Calculator or picker failures: those must fail this regression.
        if (!device.hasObject(By.text("Pixel Launcher isn't responding"))) return false
        device.findObject(By.text("Close app"))?.click()
        return true
    }
    @Before fun prepareSystemUi() {
        Configurator.getInstance().waitForIdleTimeout = 1000
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
    }
    private fun runPickerCase(photoPicker: Boolean, moveCase: String? = null) {
        unlock()
        val name = "CalcVault-picker-${System.nanoTime()}.png"
        val path = "/sdcard/Download/$name"
        val color = when (moveCase) {
            "remove" -> Color.GREEN; "keep" -> Color.YELLOW; "changed" -> Color.MAGENTA
            "missing" -> Color.CYAN; "corrupt" -> Color.GRAY; "duplicate" -> Color.DKGRAY
            else -> if (photoPicker) Color.BLUE else Color.RED
        }
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        val expectedDigest = Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
        val beforeIds = model.state.value.catalog.items.map { it.id }.toSet()
        // Shell creates the source, not the app. Reading the returned document relies on
        // the system picker's content URI grant. This is synthetic data on a disposable emulator.
        try {
            // Publish before querying the collection: its default query excludes
            // pending rows, even though the shell just inserted them. No picker
            // is opened until the complete fixture bytes have been written.
            val inserted = shell("content insert --uri content://media/external_primary/downloads --bind _display_name:s:$name --bind mime_type:s:image/png --bind relative_path:s:Download/ --bind is_pending:i:0")
            val rows = shell("content query --uri content://media/external_primary/downloads --projection _id --where \"_display_name='$name'\"")
            val fixtureId = checkNotNull(Regex("_id=(\\d+)").find(rows)?.groupValues?.get(1)) { "Fixture insertion failed: $inserted $rows" }
            val fixtureUri = "content://media/external_primary/downloads/$fixtureId"
            val written = shell("printf '%s' '${Base64.encodeToString(bytes, Base64.NO_WRAP)}' | base64 -d | content write --uri '$fixtureUri'")
            check(shell("test -s '$path' && echo SOURCE_READY").contains("SOURCE_READY")) { "Synthetic source was not written: $written" }
            dismissLauncherAnr()
            compose.onNodeWithText("Import files", useUnmergedTree = true).performClick()
            val pickerLabel = if (moveCase != null && moveCase != "duplicate") "Move to vault" else if (photoPicker) "Photos & videos" else "Documents & other files"
            compose.onNodeWithText(pickerLabel, useUnmergedTree = true).performClick()
            awaitSystemPicker(pickerLabel)
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
            // Photo Picker deliberately exposes <media-id>.<extension>, not the
            // original filename. Identify this import by its verified content.
            compose.waitUntil(30000) { !model.state.value.busy && model.state.value.catalog.items.any { it.id !in beforeIds && it.digest == expectedDigest } }
            val item = model.state.value.catalog.items.first { it.id !in beforeIds && it.digest == expectedDigest }
            if (!photoPicker) assertEquals(name, item.name)
            assertTrue(item.name.endsWith(".png"))
            assertEquals("Photos", item.category)
            assertArrayEquals(bytes, model.vault.reader(item).stream().use { it.readBytes() })
            val decoded = PrivateMedia.image(model.vault, item, 256)
            assertEquals(color, decoded.getPixel(0, 0))
            decoded.recycle()
            compose.runOnUiThread { model.message(null) }
            compose.onNodeWithText(item.name, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription(item.name)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(item.name).assertIsDisplayed()
            // Original remains intact; import never removes it.
            assertTrue(shell("test -f '$path' && echo present").contains("present"))
            assertEquals(0, model.state.value.pendingImports)
            device.pressBack()
            if (moveCase == "duplicate") {
                val itemCount = model.state.value.catalog.items.size
                compose.runOnUiThread { model.rename(item.id, "Previously imported.png") }
                compose.waitUntil(15000) { !model.state.value.busy && model.state.value.catalog.items.any { it.id == item.id && it.name == "Previously imported.png" } }
                compose.runOnUiThread { model.message(null) }
                compose.onNodeWithText("Import files", useUnmergedTree = true).performClick()
                compose.onNodeWithText("Move to vault", useUnmergedTree = true).performClick()
                awaitSystemPicker("Move to vault")
                device.wait(Until.findObject(By.text(name)), 15000)!!.click()
                if (!device.wait(Until.hasObject(By.pkg("com.calcvault")), 3000)) device.findObject(By.text(Pattern.compile("(?i)(select|open|done)")))?.click()
                compose.waitUntil(15000) { model.state.value.pendingImports == 1 }
                unlock()
                compose.waitUntil(30000) { !model.state.value.busy && model.state.value.removalNames.isNotEmpty() }
                assertEquals(itemCount, model.state.value.catalog.items.size)
                assertTrue(model.state.value.message.orEmpty().contains("1 already in vault"))
                compose.runOnUiThread { model.message(null) }
            }
            if (moveCase != null) {
                assertEquals(listOf(name), model.state.value.removalNames)
                if (moveCase == "changed") {
                    val changed = bytes + byteArrayOf(0)
                    shell("printf '%s' '${Base64.encodeToString(changed, Base64.NO_WRAP)}' | base64 -d | content write --uri '$fixtureUri'")
                }
                if (moveCase == "missing") {
                    compose.runOnUiThread { model.delete(setOf(item.id)) }
                    compose.waitUntil(15000) { !model.state.value.busy && model.state.value.catalog.items.none { it.id == item.id } }
                    compose.runOnUiThread { model.message(null) }
                }
                if (moveCase == "corrupt") java.io.File(compose.activity.noBackupFilesDir, "vault/${item.id}.cv").appendBytes(byteArrayOf(0))
                compose.onNodeWithText("Review originals", useUnmergedTree = true).performClick()
                compose.onNodeWithText("• $name", useUnmergedTree = true).assertExists()
                // Merely opening the confirmation must never delete anything.
                assertTrue(shell("test -f '$path' && echo present").contains("present"))
                if (moveCase == "keep") {
                    compose.onNode(hasText("Keep originals") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
                } else compose.onNodeWithText("Remove originals", useUnmergedTree = true).performClick()
                compose.waitUntil(30000) { !model.state.value.busy && model.state.value.removalNames.isEmpty() }
                if (moveCase in listOf("remove", "duplicate")) {
                    assertTrue(model.state.value.message.orEmpty().contains("1 original removed"))
                    assertTrue(shell("test ! -f '$path' && echo absent").contains("absent"))
                    assertTrue(shell("content query --uri content://media/external_primary/images/media --projection _id --where \"_display_name='$name'\"").contains("No result found"))
                    assertArrayEquals(bytes, model.vault.reader(item).stream().use { it.readBytes() })
                } else {
                    assertTrue(shell("test -f '$path' && echo present").contains("present"))
                    if (moveCase != "keep") assertTrue(model.state.value.message.orEmpty().contains("No removal requested"))
                }
                compose.runOnUiThread { model.message(null) }
                if (moveCase == "corrupt") {
                    compose.runOnUiThread { model.delete(setOf(item.id)) }
                    compose.waitUntil(15000) { !model.state.value.busy }
                    compose.runOnUiThread { model.message(null) }
                }
            } else assertTrue(model.state.value.removalNames.isEmpty())
            compose.onNodeWithContentDescription("Lock vault").performClick()
        } catch (e: Throwable) {
            val hierarchy = ByteArrayOutputStream()
            runCatching { device.dumpWindowHierarchy(hierarchy) }
            val nodes = Regex("<node [^>]+>").findAll(hierarchy.toString())
                .map { it.value }.filter { !it.contains("package=\"com.android.systemui\"") }
                .map { node -> Regex("(?:text|resource-id|package|content-desc|bounds)=\"[^\"]+\"").findAll(node).joinToString(" ") { it.value } }
                .filter { it.contains("text=") || it.contains("content-desc=") || it.contains("resource-id=") }
                .joinToString("\n").take(7000)
            throw AssertionError("${e.message}\nImport state: ${model.state.value.message}\nSynthetic test picker UI: $nodes", e)
        } finally {
            shell("content delete --uri content://media/external_primary/downloads --where \"_display_name='$name'\"; rm -f '$path'")
        }
    }
    @Test fun documentsPickerReturnsAfterLockAndRecreationAndImportsVerifiedPhoto() = runPickerCase(false)
    @Test fun photoPickerReturnsAfterLockAndRecreationAndImportsVerifiedPhoto() = runPickerCase(true)
    @Test fun moveRemovesOriginalFromSharedStorageOnlyAfterConfirmation() = runPickerCase(false, "remove")
    @Test fun decliningMoveKeepsBothCopies() = runPickerCase(false, "keep")
    @Test fun changedSourceIsNeverRemoved() = runPickerCase(false, "changed")
    @Test fun missingVaultCopyPreventsOriginalRemoval() = runPickerCase(false, "missing")
    @Test fun corruptedVaultCopyPreventsOriginalRemoval() = runPickerCase(false, "corrupt")
    @Test fun existingVerifiedDuplicateCanBeMovedWithoutAnotherVaultCopy() = runPickerCase(false, "duplicate")
}

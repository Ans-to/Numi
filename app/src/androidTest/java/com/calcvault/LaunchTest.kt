package com.calcvault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.calcvault.ui.VaultViewModel
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model(): VaultViewModel = ViewModelProvider(compose.activity)[VaultViewModel::class.java]
    private fun ready() { compose.waitUntil(15000) { model().state.value.ready } }
    private fun type(value: String) { value.forEach { compose.onNodeWithText(it.toString(), useUnmergedTree = true).performClick() } }
    private fun ensurePin() {
        ready()
        if (!model().state.value.configured) {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Set up private storage").performClick()
            compose.onNodeWithText("Continue").performClick()
            type("381729"); compose.onNodeWithText("=").performClick()
            type("381729"); compose.onNodeWithText("=").performClick()
            compose.waitUntil(15000) { model().state.value.configured && !model().state.value.busy }
            compose.onNodeWithText("OK").performClick()
        }
        // Isolate this test from persisted throttling intentionally exercised by other tests.
        model().auth.resetAttempts()
        compose.onNodeWithText("AC").performClick()
    }
    @Test fun ordinaryNumbersAreVisibleBeforeEqualsAndCalculatorHasNoVaultBanner() {
        ready()
        compose.onNodeWithText("AC").performClick()
        type("123456789")
        compose.onNodeWithTag("calculator_display").assertTextEquals("123456789")
        compose.onNodeWithText("Digits hidden", substring = true).assertDoesNotExist()
        compose.onNodeWithText("A little space, just for you.").assertDoesNotExist()
        compose.onNodeWithText("Set up private storage").assertDoesNotExist()
        compose.onNodeWithText("AC").performClick()
        type("1+2"); compose.onNodeWithText("=").performClick()
        compose.waitUntil(10000) { !model().state.value.busy }
        compose.onNodeWithTag("calculator_display").assertTextEquals("3")
        compose.activityRule.scenario.onActivity {
            check(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }
    @Test fun pinUnlockAndActualActivityBackgroundRelock() {
        ensurePin()
        type("381729+0"); compose.onNodeWithText("=").performClick()
        compose.waitUntil(15000) { !model().state.value.busy }
        check(!model().state.value.unlocked)
        type("381729"); compose.onNodeWithText("=").performClick()
        compose.waitUntil(15000) { model().state.value.unlocked }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        check(!model().state.value.unlocked)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("calculator_display").assertIsDisplayed()
    }
    @Test fun unlockCooldownDoesNotHideNumbersOrBlockArithmeticOrAnnounceAVault() {
        ensurePin()
        val auth = model().auth
        try {
            // Build a failure history in the past, then reserve a current delayed attempt.
            var past = System.currentTimeMillis() - 3_600_000
            repeat(6) { auth.attempt("999998".toCharArray(), past); past += 300_001 }
            auth.attempt("999998".toCharArray())
            type("654321")
            compose.onNodeWithTag("calculator_display").assertTextEquals("654321")
            compose.onNodeWithText("=").performClick()
            compose.waitUntil(10000) { !model().state.value.busy }
            compose.onNodeWithTag("calculator_display").assertTextEquals("654321")
            check(!model().state.value.unlocked && model().state.value.message == null)
            compose.onNodeWithText("+", useUnmergedTree = true).performClick()
            type("1"); compose.onNodeWithText("=").performClick()
            compose.waitUntil(10000) { !model().state.value.busy }
            compose.onNodeWithTag("calculator_display").assertTextEquals("654322")
        } finally { auth.resetAttempts() }
    }
}

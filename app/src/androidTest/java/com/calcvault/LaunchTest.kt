package com.calcvault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun installsLaunchesAndPerformsRealCalculation() {
        compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("CALCULATOR")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+", useUnmergedTree = true).performClick()
        compose.onNodeWithText("2", useUnmergedTree = true).performClick()
        compose.onNodeWithText("=", useUnmergedTree = true).performClick()
        compose.onAllNodes(androidx.compose.ui.test.hasText("3")).fetchSemanticsNodes().let { check(it.size >= 2) }
        compose.onNodeWithText("CALCULATOR").assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            check(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }
    @Test fun pinUnlockAndActualActivityBackgroundRelock() {
        lateinit var model: com.calcvault.ui.VaultViewModel
        compose.activityRule.scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[com.calcvault.ui.VaultViewModel::class.java] }
        compose.waitUntil(10000) { model.state.value.ready }
        fun enter(value: String) { compose.runOnUiThread { value.forEach { model.key(it.toString()) }; model.key("=") } }
        if (!model.state.value.configured) {
            compose.runOnUiThread { model.startPinChange() }
            enter("381729")
            compose.waitUntil(5000) { model.state.value.pinStage == 2 }
            enter("381729")
            compose.waitUntil(10000) { model.state.value.configured && !model.state.value.busy }
            compose.runOnUiThread { model.message(null) }
        }
        enter("381729+0")
        compose.waitUntil(10000) { !model.state.value.busy }
        check(!model.state.value.unlocked)
        enter("381729")
        compose.waitUntil(10000) { model.state.value.unlocked }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(5000) { !model.state.value.unlocked }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("CALCULATOR").assertIsDisplayed()
    }
}

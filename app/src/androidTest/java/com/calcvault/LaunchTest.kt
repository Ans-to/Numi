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
    }
}

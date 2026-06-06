package com.bytedance.zgx.pocketmind

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class MainActivityRuntimePermissionUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun contactLookupConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查联系人 Alice")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("查询联系人").assertIsDisplayed()
            composeRule.onNodeWithText("将按“Alice”查询联系人。").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("联系人权限：用于只读查询联系人摘要。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
        }
    }

    @Test
    fun recentScreenshotOcrConfirmationShowsImageReadRationaleAndCancelsCleanly() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("识别最近 1 张截图文字")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("读取最近截图 OCR").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("照片和图片权限：用于在你确认后读取最近 1 张截图像素，并在本地提取 OCR 文本。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
            composeRule.assertTextAbsent("工具执行结果")
        }
    }

    private fun launchReadyRemoteActivity(): ActivityScenario<MainActivity> {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        return ActivityScenario.launch(
            mainActivitySkipStartupIntent(
                context = targetContext,
                debugRemoteModelConfig = ReadyRemoteModelConfig,
            ),
        )
    }

    private fun ComposeTestRule.sendPrompt(prompt: String) {
        waitForReadyComposer()
        onNodeWithTag("composer_input").performTextClearance()
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("composer_send_button").performClick()
        confirmRemoteSendIfPresent()
    }

    private fun ComposeTestRule.waitForReadyComposer(timeoutMillis: Long = 10_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText("输入问题").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer_input").assertIsEnabled()
    }

    private fun ComposeTestRule.confirmRemoteSendIfPresent() {
        val needsConfirmation = waitForOptionalTag("remote_send_disclosure_sheet", timeoutMillis = 1_500)
        if (!needsConfirmation) return
        onNodeWithTag("remote_send_confirm_button").performClick()
        waitForTagGone("remote_send_disclosure_sheet")
    }

    private fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForTagGone(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForOptionalTag(tag: String, timeoutMillis: Long): Boolean =
        runCatching {
            waitForTag(tag, timeoutMillis = timeoutMillis)
            true
        }.getOrDefault(false)

    private fun ComposeTestRule.assertTagAbsent(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.assertTextAbsent(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }
}

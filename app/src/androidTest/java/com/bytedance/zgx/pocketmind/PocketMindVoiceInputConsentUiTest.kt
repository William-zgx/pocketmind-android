package com.bytedance.zgx.pocketmind

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_BODY
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PocketMindVoiceInputConsentUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun voiceButtonRequiresAppConsentBeforeStartingVoiceInput() {
        var startVoiceInputCount = 0

        composeRule.setContent {
            PocketMindTheme {
                PocketMindScreen(
                    state = ChatUiState(isReady = true),
                    onImportModel = {},
                    onDownloadModel = {},
                    onDownloadRecommendedModel = {},
                    onDownloadCustomModel = {},
                    onCancelDownload = {},
                    onLoadModel = {},
                    onRecommendedModelSelected = {},
                    onInstalledModelSelected = {},
                    onInferenceModeSelected = {},
                    onRemoteModelConfigChanged = {},
                    onBackendSelected = {},
                    onGenerationParametersChanged = {},
                    onResetGenerationParameters = {},
                    onCreateSession = {},
                    onSessionSelected = {},
                    onDeleteSession = {},
                    onOpenModelPage = {},
                    onSetupModelToggled = { _, _ -> },
                    onDownloadSetupModels = {},
                    onSkipFirstRunSetup = {},
                    onMemoryEnabledChanged = {},
                    onForgetLongTermMemory = {},
                    onClearLongTermMemory = {},
                    onRefreshBackgroundTasks = {},
                    onRefreshAuditEvents = {},
                    onCancelBackgroundTask = {},
                    onSetPeriodicCheckPolicy = {},
                    onDisablePeriodicCheckPolicy = {},
                    onOpenSpecialAccessSettings = {},
                    onConfirmAgentConfirmation = {},
                    onDismissAgentConfirmation = {},
                    onRecordExternalOutcome = { _, _ -> },
                    onOpenRecoveryAction = {},
                    onConfirmRemoteSendDisclosure = {},
                    onDismissRemoteSendDisclosure = {},
                    onSendMessage = {},
                    onSendPendingSharedInput = {},
                    onClearPendingSharedInput = {},
                    onStartVoiceInput = { startVoiceInputCount += 1 },
                    onCancelVoiceInput = {},
                    onFinishVoiceInput = {},
                    onPickSharedAttachment = {},
                    onVoiceInputConsumed = {},
                    onStopGeneration = {},
                )
            }
        }

        composeRule.onNodeWithTag("composer_voice_button").performClick()

        assertEquals(0, startVoiceInputCount)
        composeRule.onNodeWithTag("voice_permission_disclosure_dialog").assertIsDisplayed()
        composeRule.onNodeWithText(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY).assertIsDisplayed()
        composeRule.onNodeWithText("同意并开启语音输入").assertIsDisplayed()

        composeRule.onNodeWithTag("voice_permission_cancel_button").performClick()
        composeRule.waitForTagGone("voice_permission_disclosure_dialog")
        assertEquals(0, startVoiceInputCount)

        composeRule.onNodeWithTag("composer_voice_button").performClick()
        composeRule.onNodeWithTag("voice_permission_consent_button").performClick()

        composeRule.waitForTagGone("voice_permission_disclosure_dialog")
        assertEquals(1, startVoiceInputCount)
        composeRule.onNodeWithText(VOICE_INPUT_PRIVACY_DESCRIPTION).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTagGone(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }
}

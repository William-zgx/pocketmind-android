package com.bytedance.zgx.pocketmind.ui

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatUiState
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
import com.bytedance.zgx.pocketmind.ModelHealth
import com.bytedance.zgx.pocketmind.ModelHealthState
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.RunDataReceiptUiSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketMindScreenDisplayTest {
    @Test
    fun remoteAttachmentProtectionNoticeNamesVisionImagePath() {
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("远程模型模式"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("图片会直接发送"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("其他附件和分享文本"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("不会读取正文"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("OCR 摘录"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("不支持图片"))
    }

    @Test
    fun trustBoundaryCopyNamesLocalRemotePermissionAndDeletionControls() {
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("留在本机"))
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("LocalOnly"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("对话上下文"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("图片会随请求发送"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("OCR 摘录"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("Accessibility 文本"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("前台一次性确认"))
    }

    @Test
    fun actionParametersDisplayLinkDomainAndTargetPackage() {
        val linkRows = actionParameterDisplayRows(
            key = "uri",
            value = "https://example.com/private/path?ref=demo",
        )

        assertEquals("链接域名", linkRows[0].label)
        assertEquals("example.com", linkRows[0].value)
        assertEquals("完整链接", linkRows[1].label)
        assertEquals("https://example.com/private/path?ref=demo", linkRows[1].value)

        val packageRows = actionParameterDisplayRows(
            key = "packageName",
            value = "com.example.target",
        )

        assertEquals(1, packageRows.size)
        assertEquals("目标包", packageRows.single().label)
        assertEquals("com.example.target", packageRows.single().value)
    }

    @Test
    fun actionTextDisplayCollapsesLongTextAndKeepsLength() {
        val longText = "a".repeat(ACTION_SUMMARY_COLLAPSE_CHARS + 24)

        val collapsed = actionTextDisplay(
            text = longText,
            collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
            expanded = false,
        )
        val expanded = actionTextDisplay(
            text = longText,
            collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
            expanded = true,
        )

        assertTrue(collapsed.canToggle)
        assertEquals(longText.length, collapsed.totalChars)
        assertTrue(collapsed.text.endsWith("..."))
        assertTrue(collapsed.text.length <= ACTION_SUMMARY_COLLAPSE_CHARS)
        assertEquals(longText, expanded.text)
    }

    @Test
    fun localModelStatusDisplaysConfiguredContextWindow() {
        val status = currentModelStatus(
            ChatUiState(
                modelPath = "/tmp/model.litertlm",
                backend = BackendChoice.GPU,
                localMaxTotalTokens = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
            ),
        )

        assertTrue(status.contains("GPU"))
        assertTrue(status.contains("Token 8k"))
        assertTrue(status.endsWith("待加载"))
    }

    @Test
    fun remoteModelStatusDoesNotShowLocalContextWindow() {
        val status = currentModelStatus(
            ChatUiState(
                inferenceMode = InferenceMode.Remote,
                remoteModelConfig = RemoteModelConfig(modelName = "remote-test-model"),
                isReady = true,
            ),
        )

        assertTrue(status.contains("remote-test-model"))
        assertTrue(status.contains("远程"))
        assertTrue(!status.contains("上下文"))
    }

    @Test
    fun runDataReceiptDisplayNamesDestinationProtectionDeletionAndPersistence() {
        val text = runDataReceiptDisplayText(
            RunDataReceiptUiSummary(
                destination = "Remote",
                currentPromptPrivacy = "RemoteEligible",
                remoteHistoryCount = 3,
                localOnlyHistoryFilteredCount = 2,
                memoryHitCount = 1,
                memoryContextIncluded = false,
                deviceContextIncluded = false,
                imageAttachmentCount = 1,
                protectedSourceCount = 4,
                rawContentPersisted = false,
                protectedContentTypes = listOf("本地记忆", "设备上下文", "LocalOnly 历史"),
                deletableRecordTypes = listOf("对话消息", "Agent 轨迹", "显式记忆"),
            ),
        )

        assertTrue(text.contains("去向：远端"))
        assertTrue(text.contains("远端历史：3"))
        assertTrue(text.contains("过滤 LocalOnly：2"))
        assertTrue(text.contains("保护：本地记忆、设备上下文、LocalOnly 历史"))
        assertTrue(text.contains("可删除：对话消息、Agent 轨迹、显式记忆"))
        assertTrue(text.contains("原文持久化：否"))
    }

    @Test
    fun modelHealthDisplayShowsStructuredFallbackAndTimingMetrics() {
        val text = modelHealthDisplayText(
            ChatUiState(
                backend = BackendChoice.CPU,
                modelHealth = ModelHealth(
                    profileId = "chat-e2b",
                    state = ModelHealthState.FallbackActive,
                    backend = BackendChoice.CPU,
                    loadMs = 1234,
                    firstTokenMs = 456,
                    tokenCount = 42,
                    tokensPerSecond = 7.25,
                    fallbackBackend = BackendChoice.CPU,
                    failureReason = "GPU 初始化失败",
                ),
            ),
        )

        assertTrue(text.contains("健康：Fallback"))
        assertTrue(text.contains("backend=CPU"))
        assertTrue(text.contains("fallback=CPU"))
        assertTrue(text.contains("load=1234ms"))
        assertTrue(text.contains("first=456ms"))
        assertTrue(text.contains("tokens=42"))
        assertTrue(text.contains("speed=7.3 tok/s"))
        assertTrue(text.contains("reason=GPU 初始化失败"))
    }
}

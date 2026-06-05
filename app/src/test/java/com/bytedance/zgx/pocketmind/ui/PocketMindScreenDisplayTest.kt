package com.bytedance.zgx.pocketmind.ui

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatUiState
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
import com.bytedance.zgx.pocketmind.RemoteModelConfig
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
}

package com.bytedance.zgx.pocketmind.ui

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatUiState
import com.bytedance.zgx.pocketmind.DEFAULT_CHAT_MODEL
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
import com.bytedance.zgx.pocketmind.ModelHealth
import com.bytedance.zgx.pocketmind.ModelHealthState
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.PendingRemoteSendDisclosure
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.RunDataReceiptUiSummary
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
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
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("隐私优先"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("本地可用"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("远程多模态可选"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("必须确认执行"))
        assertTrue(PRODUCT_POSITIONING_SHORT_TEXT.contains("隐私优先"))
        assertTrue(PRODUCT_POSITIONING_SHORT_TEXT.contains("随身 AI 助手"))
        assertTrue(PRODUCT_LOCAL_VALUE_TEXT.contains("基础问答"))
        assertTrue(PRODUCT_REMOTE_VALUE_TEXT.contains("图片"))
        assertTrue(PRODUCT_ACTION_VALUE_TEXT.contains("确认或取消"))
        assertTrue(PRIVACY_POLICY_ENTRY_TEXT.contains("App 内隐私说明入口"))
        assertTrue(PRIVACY_POLICY_ENTRY_TEXT.contains("Play Data safety"))
        assertTrue(REMOTE_MODE_DISCLOSURE_TEXT.contains("可远程发送的对话上下文"))
        assertTrue(REMOTE_MODE_DISCLOSURE_TEXT.contains("每次发送前都会确认"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("离线可用"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("2.4 GB"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("远程模型"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("系统语音转写"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("只进入输入框"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("不自动发送"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("不读取本地音频文件"))
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("留在本机"))
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("LocalOnly"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("对话上下文"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("图片会随请求发送"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("OCR 摘录"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("Accessibility 文本"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("前台一次性确认"))
    }

    @Test
    fun modelPathGuidanceNamesLocalRemoteAndLightweightFallback() {
        val text = modelPathGuidanceRows(DEFAULT_CHAT_MODEL).joinToString("\n") {
            "${it.label}: ${it.body}"
        }

        assertTrue(text.contains("本地"))
        assertTrue(text.contains("离线问答"))
        assertTrue(text.contains("重新下载"))
        assertTrue(text.contains("空间不足"))
        assertTrue(text.contains("远程"))
        assertTrue(text.contains("每次发送前都会展示远程内容预览"))
        assertTrue(text.contains("主动附加"))
        assertTrue(text.contains("轻量"))
        assertTrue(text.contains("没有更小的官方推荐聊天模型"))
        assertTrue(text.contains(".litertlm"))
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
    fun actionDataBoundaryNamesExternalLocalAndBackgroundDestinations() {
        val externalRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SHARE_TEXT)
        assertTrue(externalRows.joinToString().contains("外部 App"))
        assertTrue(externalRows.joinToString().contains("未确认结果前宣称已完成"))

        val localRows = actionDataBoundaryDisplayRows(MobileActionFunctions.READ_CLIPBOARD)
        assertTrue(localRows.joinToString().contains("本机内容"))
        assertTrue(localRows.joinToString().contains("LocalOnly"))
        assertTrue(localRows.joinToString().contains("不会自动发送给远程模型"))

        val reminderRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SCHEDULE_REMINDER)
        assertTrue(reminderRows.joinToString().contains("后台任务"))
        assertTrue(reminderRows.joinToString().contains("默认留在本机"))
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
    fun remoteSendDisclosureRowsNameDestinationAndProtectedData() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                prompt = "不要展示密钥",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 2,
                localOnlyHistoryFilteredCount = 3,
                imageAttachmentCount = 1,
                protectedSourceCount = 3,
                apiKeyConfigured = true,
            ),
        ).joinToString("\n")

        assertTrue(text.contains("api.example.com"))
        assertTrue(text.contains("model-a"))
        assertTrue(text.contains("可远程发送历史 2 条"))
        assertTrue(text.contains("图片 1 张"))
        assertTrue(text.contains("图片字节会发往该远程地址"))
        assertTrue(text.contains("LocalOnly 历史 3 条"))
        assertTrue(text.contains("本地记忆"))
        assertTrue(text.contains("设备上下文"))
        assertTrue(text.contains("已配置 API Key"))
        assertTrue(!text.contains("不要展示密钥"))
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

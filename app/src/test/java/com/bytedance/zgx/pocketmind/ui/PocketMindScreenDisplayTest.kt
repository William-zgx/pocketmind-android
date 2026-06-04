package com.bytedance.zgx.pocketmind.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketMindScreenDisplayTest {
    @Test
    fun remoteAttachmentProtectionNoticeNamesUnreadPickerInputs() {
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("远程模型模式"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("不会读取分享文本"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("附件元数据"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("文件流"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("OCR 摘录"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("手动粘贴"))
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
}

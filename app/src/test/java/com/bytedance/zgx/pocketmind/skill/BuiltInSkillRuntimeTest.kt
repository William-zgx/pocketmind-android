package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillRuntimeTest {
    private val runtime = BuiltInSkillRuntime()

    @Test
    fun exposesVersionedManifestsForCoreSkills() {
        val manifests = runtime.manifests().associateBy { it.id }

        assertTrue(BuiltInSkillRuntime.EMAIL_DRAFT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.MAP_SEARCH_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.REMINDER_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CANCEL_REMINDER_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.SHARE_TEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL in manifests)
        assertTrue(manifests.values.all { it.version >= 1 })
        assertTrue(manifests.values.all { it.inputSchemaJson.contains("additionalProperties") })
        assertTrue(BuiltInSkillRuntime.CONTACTS_QUERY_SKILL in manifests)
    }

    @Test
    fun plansEmailDraftAsToolStepWithoutExecutingIt() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.COMPOSE_EMAIL,
            title = "邮件草稿",
            summary = "将打开邮件 App 并填入草稿内容。",
            parameters = mapOf("subject" to "Hi", "body" to "明天聊"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("帮我写封邮件", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.EMAIL_DRAFT_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.COMPOSE_EMAIL), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(request, step.request)
        assertEquals(draft, step.draft)
    }

    @Test
    fun plansReminderAsBackgroundToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.SCHEDULE_REMINDER,
            title = "后台提醒",
            summary = "将在 15 分钟后提醒：喝水",
            parameters = mapOf(
                "title" to "喝水",
                "body" to "提醒我 15 分钟后喝水",
                "delayMinutes" to "15",
            ),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("提醒我 15 分钟后喝水", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.SCHEDULE_REMINDER), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun plansCancelReminderAsBackgroundToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.CANCEL_REMINDER,
            title = "取消提醒",
            summary = "将取消已安排提醒：task-abc123",
            parameters = mapOf("taskId" to "task-abc123"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("取消提醒 task-abc123", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CANCEL_REMINDER_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.CANCEL_REMINDER), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun plansContactQueryAsDeviceContextToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            summary = "将按“联系人A”查询联系人。",
            parameters = mapOf("query" to "联系人A"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("查一下联系人 A", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CONTACTS_QUERY_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.QUERY_CONTACTS), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun plansClipboardReadAsContextToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val request = ToolRequest(toolName = draft.functionName, reason = draft.summary)

        val plan = runtime.plan("读取剪贴板", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.READ_CLIPBOARD), plan.manifest.requiredTools)
    }

    @Test
    fun routesClipboardSummaryShareInputToCompositePlan() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val request = ToolRequest(toolName = draft.functionName, reason = draft.summary)

        val plan = runtime.plan("总结剪贴板并分享", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(3, plan.steps.size)
        val readStep = plan.steps.first()
        require(readStep is SkillStep.ToolStep)
        assertEquals(request.id, readStep.request.id)
        assertEquals(draft, readStep.draft)
    }

    @Test
    fun plansShareTextAsShareSheetToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            summary = "将打开系统分享面板并填入文本。",
            parameters = mapOf("text" to "明天十点开会"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("分享这段文字：明天十点开会", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.SHARE_TEXT_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.SHARE_TEXT), plan.manifest.requiredTools)
    }

    @Test
    fun plansDeepLinkAsNavigationToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_DEEP_LINK,
            title = "打开深链",
            summary = "将打开外部链接",
            parameters = mapOf("uri" to "https://example.com"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("打开 https://example.com", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.OPEN_DEEP_LINK), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun plansClipboardSummaryShareAsOrderedCompositeSkill() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")

        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
            plan.manifest.requiredTools,
        )
        assertTrue(plan.validateStructure().isValid)
        assertEquals(3, plan.steps.size)

        val readStep = plan.steps[0]
        require(readStep is SkillStep.ToolStep)
        assertEquals("read_clipboard", readStep.id)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, readStep.request.toolName)
        assertTrue(readStep.dependsOn.isEmpty())

        val summarizeStep = plan.steps[1]
        require(summarizeStep is SkillStep.ModelStep)
        assertEquals("summarize_clipboard", summarizeStep.id)
        assertEquals(listOf(readStep.id), summarizeStep.dependsOn)
        assertEquals(mapOf("clipboardText" to "read_clipboard.text"), summarizeStep.inputBindings)
        assertEquals("shareText", summarizeStep.outputKey)
        assertTrue(summarizeStep.keepsSensitiveInputLocal)

        val shareStep = plan.steps[2]
        require(shareStep is SkillStep.ToolStep)
        assertEquals("share_summary", shareStep.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareStep.request.toolName)
        assertEquals(listOf(summarizeStep.id), shareStep.dependsOn)
        assertEquals(mapOf("text" to "summarize_clipboard.shareText"), shareStep.argumentBindings)
    }

    @Test
    fun validateStructureRejectsUnorderedOrInvalidCompositePlan() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")
        val invalidPlan = plan.copy(
            steps = listOf(
                plan.steps[2],
                plan.steps[1],
                plan.steps[0],
            ),
        )

        val validation = invalidPlan.validateStructure()

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("share_summary depends on missing or later step") })
        assertTrue(validation.errors.any { it.contains("summarize_clipboard depends on missing or later step") })
    }
}

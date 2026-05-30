package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import java.util.UUID

class BuiltInSkillRuntime : SkillRuntime {
    private val manifestsById = builtInSkillManifests.associateBy { it.id }
    private val skillByToolName = mapOf(
        MobileActionFunctions.COMPOSE_EMAIL to EMAIL_DRAFT_SKILL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT to CALENDAR_DRAFT_SKILL,
        MobileActionFunctions.SEARCH_MAPS to MAP_SEARCH_SKILL,
        MobileActionFunctions.WEB_SEARCH to INFORMATION_LOOKUP_SKILL,
        MobileActionFunctions.OPEN_WIFI_SETTINGS to DEVICE_SETTINGS_SKILL,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS to DEVICE_SETTINGS_SKILL,
        MobileActionFunctions.OPEN_DEEP_LINK to DEEP_LINK_NAVIGATION_SKILL,
        MobileActionFunctions.OPEN_APP_INTENT to OPEN_APP_INTENT_SKILL,
        MobileActionFunctions.SCHEDULE_REMINDER to REMINDER_SKILL,
        MobileActionFunctions.CANCEL_REMINDER to CANCEL_REMINDER_SKILL,
        MobileActionFunctions.QUERY_CONTACTS to CONTACTS_QUERY_SKILL,
        MobileActionFunctions.QUERY_FOREGROUND_APP to FOREGROUND_APP_QUERY_SKILL,
        MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS to RECENT_NOTIFICATIONS_QUERY_SKILL,
        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY to CALENDAR_AVAILABILITY_QUERY_SKILL,
        MobileActionFunctions.READ_CLIPBOARD to CLIPBOARD_CONTEXT_SKILL,
        MobileActionFunctions.SHARE_TEXT to SHARE_TEXT_SKILL,
    )

    override fun manifests(): List<SkillManifest> = builtInSkillManifests

    override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? {
        if (request.toolName == MobileActionFunctions.READ_CLIPBOARD && input.requestsClipboardSummaryShare()) {
            return planClipboardSummaryShare(
                input = input,
                readRequest = request,
                readDraft = draft,
            )
        }
        val skillId = skillByToolName[request.toolName] ?: return null
        val manifest = manifestsById.getValue(skillId)
        if (request.toolName !in manifest.requiredTools) return null
        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                arguments = request.arguments,
                reason = draft.summary.ifBlank { input },
            ),
            manifest = manifest,
            steps = listOf(SkillStep.ToolStep(request, draft)),
        )
    }

    fun planClipboardSummaryShare(
        input: String,
        readRequest: ToolRequest? = null,
        readDraft: ActionDraft? = null,
    ): SkillPlan {
        val manifest = manifestsById.getValue(CLIPBOARD_SUMMARY_SHARE_SKILL)
        val readStepId = "read_clipboard"
        val summarizeStepId = "summarize_clipboard"

        val resolvedReadDraft = readDraft ?: ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本，用于生成可分享摘要。",
            parameters = emptyMap(),
        )
        val resolvedReadRequest = readRequest ?: ToolRequest(
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = resolvedReadDraft.summary,
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享摘要",
            summary = "将打开系统分享面板并填入上一步生成的摘要。",
            parameters = emptyMap(),
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            reason = shareDraft.summary,
        )

        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = CLIPBOARD_SUMMARY_SHARE_SKILL,
                arguments = mapOf("input" to input),
                reason = input,
            ),
            manifest = manifest,
            steps = listOf(
                SkillStep.ToolStep(
                    id = readStepId,
                    request = resolvedReadRequest,
                    draft = resolvedReadDraft,
                ),
                SkillStep.ModelStep(
                    id = summarizeStepId,
                    dependsOn = listOf(readStepId),
                    title = "摘要剪贴板内容",
                    instruction = "把用户确认读取的剪贴板文本整理成适合分享的简短摘要，语言尽量跟随用户请求。",
                    inputBindings = mapOf("clipboardText" to "$readStepId.text"),
                    outputKey = "shareText",
                    keepsSensitiveInputLocal = true,
                ),
                SkillStep.ToolStep(
                    id = "share_summary",
                    dependsOn = listOf(summarizeStepId),
                    request = shareRequest,
                    draft = shareDraft,
                    argumentBindings = mapOf("text" to "$summarizeStepId.shareText"),
                ),
            ),
        )
    }

    companion object {
        const val EMAIL_DRAFT_SKILL = "email_draft_skill"
        const val CALENDAR_DRAFT_SKILL = "calendar_draft_skill"
        const val MAP_SEARCH_SKILL = "map_search_skill"
        const val INFORMATION_LOOKUP_SKILL = "information_lookup_skill"
        const val DEVICE_SETTINGS_SKILL = "device_settings_skill"
        const val REMINDER_SKILL = "reminder_skill"
        const val CANCEL_REMINDER_SKILL = "cancel_reminder_skill"
        const val CONTACTS_QUERY_SKILL = "contacts_query_skill"
        const val FOREGROUND_APP_QUERY_SKILL = "foreground_app_query_skill"
        const val RECENT_NOTIFICATIONS_QUERY_SKILL = "recent_notifications_query_skill"
        const val CALENDAR_AVAILABILITY_QUERY_SKILL = "calendar_availability_query_skill"
        const val CLIPBOARD_CONTEXT_SKILL = "clipboard_context_skill"
        const val SHARE_TEXT_SKILL = "share_text_skill"
        const val CLIPBOARD_SUMMARY_SHARE_SKILL = "clipboard_summary_share_skill"
        const val DEEP_LINK_NAVIGATION_SKILL = "deep_link_navigation_skill"
        const val OPEN_APP_INTENT_SKILL = "open_app_intent_skill"
    }
}

private fun String.requestsClipboardSummaryShare(): Boolean {
    val normalized = lowercase()
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksForSummary && asksToShare
}

private val simpleTextInputSchema = """
    {
      "type": "object",
      "required": ["input"],
      "properties": {
        "input": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

    private val builtInSkillManifests = listOf(
        SkillManifest(
            id = BuiltInSkillRuntime.OPEN_APP_INTENT_SKILL,
            version = 1,
            title = "应用 Intent 跳转",
            description = "将应用包名 / 类名参数转为应用跳转请求，由用户确认后执行。",
            triggerExamples = listOf("打开 com.tencent.mm", "打开 应用包名 com.example.app"),
            requiredTools = listOf(MobileActionFunctions.OPEN_APP_INTENT),
            inputSchemaJson = simpleTextInputSchema,
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        ),
        SkillManifest(
            id = BuiltInSkillRuntime.EMAIL_DRAFT_SKILL,
            version = 1,
            title = "邮件草稿",
        description = "把自然语言请求整理成邮件草稿工具调用，不直接发送邮件。",
        triggerExamples = listOf("帮我写封邮件", "draft an email"),
        requiredTools = listOf(MobileActionFunctions.COMPOSE_EMAIL),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL,
        version = 1,
        title = "日程草稿",
        description = "把自然语言请求整理成日历新建事件工具调用。",
        triggerExamples = listOf("帮我建个日程", "add a calendar event"),
        requiredTools = listOf(MobileActionFunctions.CREATE_CALENDAR_EVENT),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.MAP_SEARCH_SKILL,
        version = 1,
        title = "路线查询",
        description = "提取地点或路线关键词并交给地图搜索工具。",
        triggerExamples = listOf("查去机场的路线", "search maps for coffee nearby"),
        requiredTools = listOf(MobileActionFunctions.SEARCH_MAPS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL,
        version = 1,
        title = "信息查找",
        description = "把需要外部信息的请求整理成受确认保护的网页搜索工具调用。",
        triggerExamples = listOf("帮我查一下", "look up Kotlin"),
        requiredTools = listOf(MobileActionFunctions.WEB_SEARCH),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
        version = 1,
        title = "设备设置入口",
        description = "打开受控系统设置入口，由用户在系统页面内继续操作。",
        triggerExamples = listOf("打开 Wi-Fi 设置", "打开手电筒设置"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.REMINDER_SKILL,
        version = 1,
        title = "后台提醒",
        description = "把自然语言提醒请求整理成本地后台提醒工具调用。",
        triggerExamples = listOf("提醒我 10 分钟后喝水", "remind me in 1 hour"),
        requiredTools = listOf(MobileActionFunctions.SCHEDULE_REMINDER),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CANCEL_REMINDER_SKILL,
        version = 1,
        title = "取消提醒",
        description = "根据任务 id 取消已安排的提醒，避免后续触发。",
        triggerExamples = listOf("取消提醒 task-abc123", "撤销提醒 task-xyz-1"),
        requiredTools = listOf(MobileActionFunctions.CANCEL_REMINDER),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CONTACTS_QUERY_SKILL,
        version = 1,
        title = "联系人查询",
        description = "把联系人检索请求整理成本机联系人读取动作。",
        triggerExamples = listOf("查找 李雷", "搜索联系人 张三"),
        requiredTools = listOf(MobileActionFunctions.QUERY_CONTACTS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.FOREGROUND_APP_QUERY_SKILL,
        version = 1,
        title = "前台应用查询",
        description = "读取当前前台应用的包名和应用名，仅返回当前可见应用信息。",
        triggerExamples = listOf("当前应用是什么", "查一下前台 App"),
        requiredTools = listOf(MobileActionFunctions.QUERY_FOREGROUND_APP),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.RECENT_NOTIFICATIONS_QUERY_SKILL,
        version = 1,
        title = "近期通知查询",
        description = "读取当前应用最近一段时间的通知摘要，仅返回数量和关键字段。",
        triggerExamples = listOf("查看最近通知", "最近5条通知"),
        requiredTools = listOf(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CALENDAR_AVAILABILITY_QUERY_SKILL,
        version = 1,
        title = "日历忙闲查询",
        description = "查询某一时间窗内的日历忙闲摘要，用于安排任务或确认空档。",
        triggerExamples = listOf("查询 9:00 到 10:00 是否有空", "查一下忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"),
        requiredTools = listOf(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL,
        version = 1,
        title = "剪贴板上下文",
        description = "在用户明确要求时读取当前剪贴板文本。",
        triggerExamples = listOf("读取剪贴板", "summarize my clipboard"),
        requiredTools = listOf(MobileActionFunctions.READ_CLIPBOARD),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.SHARE_TEXT_SKILL,
        version = 1,
        title = "系统分享",
        description = "把文本放入 Android 系统分享面板，由用户选择目标应用。",
        triggerExamples = listOf("分享这段文字", "share this text"),
        requiredTools = listOf(MobileActionFunctions.SHARE_TEXT),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
        version = 1,
        title = "剪贴板摘要分享",
        description = "读取剪贴板文本，先由本地模型生成摘要，再通过系统分享面板外发。",
        triggerExamples = listOf("总结剪贴板并分享", "summarize my clipboard and share it"),
        requiredTools = listOf(
            MobileActionFunctions.READ_CLIPBOARD,
            MobileActionFunctions.SHARE_TEXT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.HighExternalSend,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL,
        version = 1,
        title = "深链跳转",
        description = "将用户请求转化为外部链接或深度链接跳转工具调用。",
        triggerExamples = listOf("打开 https://example.com", "打开 myapp://example"),
        requiredTools = listOf(MobileActionFunctions.OPEN_DEEP_LINK),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
)

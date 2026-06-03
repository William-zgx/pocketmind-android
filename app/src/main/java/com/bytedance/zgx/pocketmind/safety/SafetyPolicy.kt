package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolSpec

data class SafetyContext(
    val userConfirmed: Boolean,
)

data class SafetyDecision(
    val outcome: SafetyOutcome,
    val reason: String,
)

enum class SafetyOutcome {
    Allow,
    RequireConfirmation,
    Reject,
}

class SafetyPolicy {
    fun evaluate(
        spec: ToolSpec,
        request: ToolRequest,
        context: SafetyContext,
    ): SafetyDecision {
        if (spec.riskLevel.requiresHardConfirmation() && spec.confirmationPolicy != ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} has ${spec.riskLevel} risk and must require confirmation.",
            )
        }

        if (spec.permissions.any { permission -> permission in confirmationRequiredPermissions } &&
            spec.confirmationPolicy != ConfirmationPolicy.Required
        ) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} crosses a device, external app, background, notification, permission, or private-read boundary and must require confirmation.",
            )
        }

        if (!context.userConfirmed && spec.confirmationPolicy == ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.RequireConfirmation,
                reason = "Tool ${request.toolName} requires user confirmation before execution.",
            )
        }

        if (!context.userConfirmed && request.requiresSensitiveNetworkQueryConfirmation()) {
            return SafetyDecision(
                outcome = SafetyOutcome.RequireConfirmation,
                reason = "Web search query may contain personal or secret data and requires confirmation before network access.",
            )
        }

        return SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Tool ${request.toolName} is allowed by current safety policy.",
        )
    }

    private fun RiskLevel.requiresHardConfirmation(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment

    private fun ToolRequest.requiresSensitiveNetworkQueryConfirmation(): Boolean =
        toolName == MobileActionFunctions.WEB_SEARCH &&
            arguments["query"].orEmpty().containsSensitiveNetworkSearchContent()

    private fun String.containsSensitiveNetworkSearchContent(): Boolean {
        val normalized = lowercase()
        return emailPattern.containsMatchIn(this) ||
            phonePattern.containsMatchIn(this) ||
            chineseIdPattern.containsMatchIn(this) ||
            secretTokenPattern.containsMatchIn(this) ||
            personalChineseKeywordPattern.containsMatchIn(this) ||
            personalEnglishKeywordPattern.containsMatchIn(normalized)
    }

    private companion object {
        val confirmationRequiredPermissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
            ToolPermission.RequiresAndroidRuntimePermission,
            ToolPermission.SchedulesBackgroundWork,
            ToolPermission.PostsNotification,
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsAccessibilityText,
            ToolPermission.RequiresMediaProjectionConsent,
            ToolPermission.ReadsDeviceContext,
        )
        val emailPattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val phonePattern = Regex("""(?<!\d)(?:\+?\d[\d\s-]{6,}\d|1[3-9]\d{9})(?!\d)""")
        val chineseIdPattern = Regex("""(?<!\d)\d{17}[0-9Xx](?!\d)""")
        val secretTokenPattern = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b""")
        val personalChineseKeywordPattern =
            Regex("""(我|我的|本人|自己).{0,12}(手机号|电话|邮箱|住址|地址|身份证|工号|银行卡|账号|密码|口令|令牌|密钥|API\s*Key)""")
        val personalEnglishKeywordPattern =
            Regex("""\b(my|mine|personal|private)\b.{0,24}\b(phone|email|address|id|employee\s*id|bank|account|password|token|secret|api\s*key)\b""")
    }
}

package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapability
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private val policy = SafetyPolicy()

    @Test
    fun requiredConfirmationToolWaitsUntilUserConfirms() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
        )
        val request = ToolRequest(toolName = spec.name)

        val beforeConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = false),
        )
        val afterConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
        assertEquals(SafetyOutcome.Allow, afterConfirmation.outcome)
    }

    @Test
    fun highRiskToolsCannotSkipConfirmation() {
        val spec = toolSpec(
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun externalTextToolsCannotRunWithoutConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.SendsTextToExternalApp),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun privateReadToolsCannotSkipConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.ReadsDeviceContext, ToolPermission.ReadsContacts),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun boundaryPermissionsCannotSkipConfirmationPolicy() {
        boundaryPermissions.forEach { permission ->
            listOf(ConfirmationPolicy.Optional, ConfirmationPolicy.NotRequired).forEach { confirmationPolicy ->
                val spec = toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    confirmationPolicy = confirmationPolicy,
                    permissions = setOf(permission),
                )

                val decision = policy.evaluate(
                    spec = spec,
                    request = ToolRequest(toolName = spec.name),
                    context = SafetyContext(userConfirmed = true),
                )

                assertEquals("permission=$permission policy=$confirmationPolicy", SafetyOutcome.Reject, decision.outcome)
            }
        }
    }

    @Test
    fun registeredBoundaryToolsRequireConfirmationBeforeExecution() {
        val boundarySpecs = ToolRegistry().specs()
            .filter { spec ->
                spec.riskLevel.requiresHardConfirmationForTest() ||
                    spec.permissions.any { permission -> permission in boundaryPermissions }
            }

        assertTrue(boundarySpecs.isNotEmpty())
        boundarySpecs.forEach { spec ->
            val beforeConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = false),
            )
            val afterConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = true),
            )

            assertEquals(spec.name, ConfirmationPolicy.Required, spec.confirmationPolicy)
            assertEquals(spec.name, SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
            assertEquals(spec.name, SafetyOutcome.Allow, afterConfirmation.outcome)
        }
    }

    @Test
    fun publicWebSearchQueryCanRunWithoutConfirmation() {
        val spec = ToolRegistry().specFor(MobileActionFunctions.WEB_SEARCH)
        requireNotNull(spec)

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气怎么样"),
            ),
            context = SafetyContext(userConfirmed = false),
        )

        assertEquals(SafetyOutcome.Allow, decision.outcome)
    }

    @Test
    fun sensitiveWebSearchQueryRequiresConfirmationBeforeNetworkAccess() {
        val spec = ToolRegistry().specFor(MobileActionFunctions.WEB_SEARCH)
        requireNotNull(spec)
        val sensitiveQueries = listOf(
            "搜索我的手机号 13800138000 有没有泄露",
            "look up my email alex@example.com",
            "帮我查我的地址附近有什么",
            "search " + "sk-" + "1234567890abcdef1234567890abcdef",
        )

        sensitiveQueries.forEach { query ->
            val beforeConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(
                    toolName = MobileActionFunctions.WEB_SEARCH,
                    arguments = mapOf("query" to query),
                ),
                context = SafetyContext(userConfirmed = false),
            )
            val afterConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(
                    toolName = MobileActionFunctions.WEB_SEARCH,
                    arguments = mapOf("query" to query),
                ),
                context = SafetyContext(userConfirmed = true),
            )

            assertEquals(query, SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
            assertEquals(query, SafetyOutcome.Allow, afterConfirmation.outcome)
        }
    }

    private fun toolSpec(
        riskLevel: RiskLevel,
        confirmationPolicy: ConfirmationPolicy,
        permissions: Set<ToolPermission> = emptySet(),
    ): ToolSpec =
        ToolSpec(
            name = "test_tool",
            title = "Test Tool",
            description = "A test tool.",
            inputSchemaJson = "{}",
            capability = ToolCapability.ExternalNavigation,
            permissions = permissions,
            riskLevel = riskLevel,
            confirmationPolicy = confirmationPolicy,
        )

    private fun RiskLevel.requiresHardConfirmationForTest(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment

    private companion object {
        val boundaryPermissions = setOf(
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
    }
}

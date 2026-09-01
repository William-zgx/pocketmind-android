package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy

/**
 * Execution-time facts that are intentionally re-read immediately before dispatch.
 * A null confirmation or capability set is ambiguous and therefore fails closed.
 */
data class ToolExecutionAuthorizationContext(
    val userConfirmed: Boolean?,
    val availableCapabilities: Set<ToolCapability>?,
)

fun interface ToolExecutionAuthorizationContextProvider {
    fun contextFor(request: ToolRequest): ToolExecutionAuthorizationContext?
}

/** Revalidates registry and safety policy at the last boundary before a tool is dispatched. */
class ToolExecutionAuthorizer(
    private val toolRegistry: ToolRegistry,
    private val safetyPolicy: SafetyPolicy,
    private val contextProvider: ToolExecutionAuthorizationContextProvider,
) {
    fun authorize(request: ToolRequest): ToolResult? {
        toolRegistry.validate(request)?.let { return it }
        val spec = toolRegistry.specFor(request.toolName)
            ?: return request.rejected("Unknown tool at final execution authorization: ${request.toolName}")
        val context = contextProvider.contextFor(request)
            ?: return request.authorizationRejected("Final execution authorization context is unavailable.")
        val userConfirmed = context.userConfirmed
            ?: return request.authorizationRejected("Final execution confirmation context is unclear.")
        val availableCapabilities = context.availableCapabilities
            ?: return request.authorizationRejected("Final execution capability context is unclear.")
        if (spec.capability !in availableCapabilities) {
            return request.authorizationRejected(
                "Tool ${request.toolName} capability ${spec.capability} is unavailable at execution time.",
            )
        }

        val decision = safetyPolicy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = userConfirmed),
        )
        return when (decision.outcome) {
            SafetyOutcome.Allow -> null
            SafetyOutcome.RequireConfirmation,
            SafetyOutcome.Reject,
            -> request.authorizationRejected(decision.reason)
        }
    }

    private fun ToolRequest.authorizationRejected(reason: String): ToolResult =
        rejected(
            summary = "Tool execution authorization rejected: $reason",
            data = mapOf("toolName" to toolName),
        )
}

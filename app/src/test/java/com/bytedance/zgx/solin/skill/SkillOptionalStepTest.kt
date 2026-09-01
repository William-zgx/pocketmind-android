package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillOptionalStepTest {
    private val progressor = SkillRunProgressor()
    private val toolRegistry = ToolRegistry.fromSupportedActions()

    @Test
    fun failedOptionalStepContinuesToNextStep() {
        val plan = planWithWait(waitOptional = true)
        val waitStep = plan.steps[0] as SkillStep.ToolStep
        val tapStep = plan.steps[1] as SkillStep.ToolStep

        val progression = progressor.nextToolAfterToolResult(
            skillPlan = plan,
            requestedRequestIds = setOf(waitStep.request.id),
            result = ToolResult(
                requestId = waitStep.request.id,
                status = ToolStatus.Failed,
                summary = "wait timed out",
            ),
        )

        require(progression is SkillToolResultProgression.BoundTool) {
            "expected the run to continue past the optional step, got $progression"
        }
        assertEquals(tapStep.id, progression.toolStep.id)
    }

    @Test
    fun failedRequiredStepStillEndsTheRun() {
        val plan = planWithWait(waitOptional = false)
        val waitStep = plan.steps[0] as SkillStep.ToolStep

        val progression = progressor.nextToolAfterToolResult(
            skillPlan = plan,
            requestedRequestIds = setOf(waitStep.request.id),
            result = ToolResult(
                requestId = waitStep.request.id,
                status = ToolStatus.Failed,
                summary = "wait timed out",
            ),
        )

        assertEquals(SkillToolResultProgression.None, progression)
    }

    @Test
    fun succeedingOptionalStepBehavesLikeAnyOtherStep() {
        val plan = planWithWait(waitOptional = true)
        val waitStep = plan.steps[0] as SkillStep.ToolStep
        val tapStep = plan.steps[1] as SkillStep.ToolStep

        val progression = progressor.nextToolAfterToolResult(
            skillPlan = plan,
            requestedRequestIds = setOf(waitStep.request.id),
            result = ToolResult(
                requestId = waitStep.request.id,
                status = ToolStatus.Succeeded,
                summary = "settled",
            ),
        )

        require(progression is SkillToolResultProgression.BoundTool)
        assertEquals(tapStep.id, progression.toolStep.id)
    }

    @Test
    fun planBindingAnOptionalStepOutputIsRejected() {
        val plan = planWithWait(waitOptional = true, tapBindsWaitOutput = true)

        val validation = plan.validateStructure(toolRegistry)

        assertTrue(
            "expected a binding-source error, got ${validation.errors}",
            validation.errors.any { error -> error.contains("optional step that may be skipped") },
        )
    }

    @Test
    fun planBindingARequiredStepOutputStaysValid() {
        val plan = planWithWait(waitOptional = false, tapBindsWaitOutput = true)

        val validation = plan.validateStructure(toolRegistry)

        assertTrue(
            "unexpected errors: ${validation.errors}",
            validation.errors.none { error -> error.contains("optional step that may be skipped") },
        )
    }

    /** A settling wait followed by a tap, mirroring the shape of the built-in UI-search skills. */
    private fun planWithWait(
        waitOptional: Boolean,
        tapBindsWaitOutput: Boolean = false,
    ): SkillPlan {
        val waitParameters = mapOf("timeoutMillis" to "800")
        val waitStep = SkillStep.ToolStep(
            id = "wait_field",
            request = ToolRequest(
                id = "wait-request",
                toolName = MobileActionFunctions.UI_WAIT,
                arguments = waitParameters,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.UI_WAIT,
                title = "等待",
                summary = "等待界面稳定。",
                parameters = waitParameters,
            ),
            optional = waitOptional,
        )
        val tapParameters = if (tapBindsWaitOutput) emptyMap() else mapOf("target" to "搜索入口")
        val tapStep = SkillStep.ToolStep(
            id = "tap_entry",
            dependsOn = listOf(waitStep.id),
            request = ToolRequest(
                id = "tap-request",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = tapParameters,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.UI_TAP,
                title = "点击",
                summary = "点击搜索入口。",
                parameters = tapParameters,
            ),
            argumentBindings = if (tapBindsWaitOutput) {
                mapOf("target" to "${waitStep.id}.summary")
            } else {
                emptyMap()
            },
        )
        return testSkillPlan(
            skillId = "optional_wait_probe",
            requiredTools = listOf(MobileActionFunctions.UI_WAIT, MobileActionFunctions.UI_TAP),
            steps = listOf(waitStep, tapStep),
        )
    }
}

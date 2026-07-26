package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Covers [DeadLoopDetectionReplanner] Rule 2 (same-screen-no-progress), which was previously a
 * dormant TODO. Rule 2 fires when the last [maxSameScreen] screen-observation fingerprints
 * describe the same surface despite intervening actions, forcing a back press to break the stall.
 */
class DeadLoopDetectionReplannerTest {
    private val replanner = DeadLoopDetectionReplanner()

    @Test
    fun forcesBackWhenSameScreenObservedMaxTimes() {
        val replan = replanner.planNext(
            contextWith(
                previousToolName = MobileActionFunctions.UI_TAP,
                fingerprints = List(3) { frozenFingerprint(capturedAtMillis = it.toLong()) },
            ),
        )

        assertNotNull("Rule 2 should fire on a frozen screen", replan)
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replan!!.request.toolName)
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replan.draft.functionName)
    }

    @Test
    fun doesNotFireWhenScreenProgresses() {
        val progressing = listOf(
            frozenFingerprint(elementCount = 10, capturedAtMillis = 0L),
            frozenFingerprint(elementCount = 12, capturedAtMillis = 1L),
            frozenFingerprint(elementCount = 15, capturedAtMillis = 2L),
        )

        val replan = replanner.planNext(
            contextWith(previousToolName = MobileActionFunctions.UI_TAP, fingerprints = progressing),
        )

        assertNull("Rule 2 must not fire when the screen keeps changing", replan)
    }

    @Test
    fun doesNotFireBelowSameScreenThreshold() {
        val replan = replanner.planNext(
            contextWith(
                previousToolName = MobileActionFunctions.UI_TAP,
                fingerprints = List(2) { frozenFingerprint(capturedAtMillis = it.toLong()) },
            ),
        )

        assertNull("Rule 2 needs at least maxSameScreen observations", replan)
    }

    @Test
    fun doesNotForceBackWhenAlreadyPressingBack() {
        val replan = replanner.planNext(
            contextWith(
                previousToolName = MobileActionFunctions.UI_PRESS_BACK,
                fingerprints = List(3) { frozenFingerprint(capturedAtMillis = it.toLong()) },
            ),
        )

        assertNull("Rule 2 must not force another back press when already at back", replan)
    }

    @Test
    fun rule1DoesNotFireOnRepeatedObserveOrWait() {
        // Repeated observe/wait must NOT be treated as a dead loop by Rule 1 — an agent legitimately
        // re-observes / waits while a screen loads (same-screen stalls are Rule 2's job). Provide
        // progressing fingerprints so Rule 2 also stays silent, isolating Rule 1.
        listOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, MobileActionFunctions.UI_WAIT).forEach { tool ->
            val priorRequests = List(4) { index ->
                ToolRequest(id = "$tool-$index", toolName = tool, arguments = emptyMap())
            }
            val progressing = List(3) { frozenFingerprint(elementCount = 8 + it, capturedAtMillis = it.toLong()) }
            val context = AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-observe-wait",
                    input = "等待屏幕加载",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = priorRequests.last(),
                observedResult = ToolResult(
                    requestId = priorRequests.last().id,
                    status = ToolStatus.Succeeded,
                    summary = "observed",
                ),
                priorRequests = priorRequests,
                screenObservationHistory = progressing,
            )

            assertNull("Rule 1 must not fire on repeated $tool", replanner.planNext(context))
        }
    }

    private fun frozenFingerprint(
        elementCount: Int = 8,
        capturedAtMillis: Long = 0L,
    ): ScreenObservationFingerprint =
        ScreenObservationFingerprint(
            packageName = "com.example.app",
            textSummary = "frozen-digest",
            elementCount = elementCount,
            capturedAtMillis = capturedAtMillis,
        )

    private fun contextWith(
        previousToolName: String,
        fingerprints: List<ScreenObservationFingerprint>,
    ): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "req-1",
            toolName = previousToolName,
            arguments = emptyMap(),
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-deadloop",
                input = "在应用里找到入口",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "observed",
            ),
            priorRequests = listOf(previousRequest),
            screenObservationHistory = fingerprints,
        )
    }
}

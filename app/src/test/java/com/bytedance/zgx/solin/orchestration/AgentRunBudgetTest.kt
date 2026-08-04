package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression coverage for the anti-runaway gates in [AgentRunBudget].
 *
 * Two properties matter here and are easy to regress silently:
 *  1. a step history that could not be read must FAIL CLOSED (contract C3) — the previous
 *     `runCatching { … }.getOrDefault(emptyList())` in the trace store made a DB hiccup look like
 *     "zero steps so far", i.e. it turned the step-limit gate off;
 *  2. a run must also be bounded in the TIME domain, because a local model generation has no
 *     timeout and step counts alone cannot bound total run duration.
 */
class AgentRunBudgetTest {
    @Test
    fun stepBudgetsAreNotExceededWhileHistoryIsReadableAndUnderTheCap() {
        val budget = budget(toolRequests = listOf(request("a")), observationDecisions = 1)

        assertFalse(budget.toolStepBudgetExceeded(RUN_ID))
        assertFalse(budget.observationDecisionBudgetExceeded(RUN_ID))
    }

    @Test
    fun degradedStepHistoryFailsBothStepDerivedBudgetsClosed() {
        // Zero recorded steps AND zero observation decisions: without the degradation signal this is
        // indistinguishable from a fresh run, so both gates would report "plenty of budget left".
        val budget = budget(
            toolRequests = emptyList(),
            observationDecisions = 0,
            stepHistoryDegraded = true,
        )

        assertTrue(budget.toolStepBudgetExceeded(RUN_ID))
        assertTrue(budget.observationDecisionBudgetExceeded(RUN_ID))
    }

    @Test
    fun degradationProbeFailureItselfDoesNotOpenTheGate() {
        // A store whose degradation probe throws must not crash the budget check; the fallback is the
        // ordinary count-based decision, which here is still under the cap.
        val budget = AgentRunBudget(
            maxRunToolSteps = 2,
            maxObservationDecisions = 2,
            profilesByRunId = ConcurrentHashMap(),
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            stepHistoryDegraded = { error("probe blew up") },
        )

        assertFalse(budget.toolStepBudgetExceeded(RUN_ID))
        assertFalse(budget.observationDecisionBudgetExceeded(RUN_ID))
    }

    @Test
    fun degradedStepHistoryIsScopedToTheAffectedRun() {
        val budget = AgentRunBudget(
            maxRunToolSteps = 10,
            maxObservationDecisions = 10,
            profilesByRunId = ConcurrentHashMap(),
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            stepHistoryDegraded = { runId -> runId == RUN_ID },
        )

        assertTrue(budget.toolStepBudgetExceeded(RUN_ID))
        assertFalse(budget.toolStepBudgetExceeded("run-healthy"))
    }

    @Test
    fun runDeadlineIsNotExceededBeforeTheWallClockBudgetElapses() {
        var now = START_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { now },
        )

        now = START_MILLIS + SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS - 1
        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun runDeadlineIsExceededOnceTheWallClockBudgetElapses() {
        var now = START_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { now },
        )

        now = START_MILLIS + SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS
        assertTrue(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun unknownRunStartTimeDoesNotFailTheRunImmediately() {
        // Restored runs (or callers that do not track start times) report a null start. Treating that
        // as an instant expiry would break restore; the step budgets still bound such runs.
        val budget = budget(runStartedAt = null, nowMillis = { Long.MAX_VALUE })

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun backwardsClockJumpDoesNotFailTheRun() {
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { START_MILLIS - 60_000L },
        )

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun nonPositiveWallClockBudgetDisablesTheDeadline() {
        val budget = AgentRunBudget(
            maxRunToolSteps = 10,
            maxObservationDecisions = 10,
            profilesByRunId = ConcurrentHashMap(),
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            runStartedAtMillis = { START_MILLIS },
            maxRunWallClockMillis = 0L,
            nowMillis = { START_MILLIS + 10 * 60_000L },
        )

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun wallClockDeadlineConstantIsFiveMinutes() {
        // Guards the audited worst-case figure documented in SolinConstants.AgentLoop.
        assertEquals(5 * 60_000L, SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS)
    }

    private fun budget(
        toolRequests: List<ToolRequest> = emptyList(),
        observationDecisions: Int = 0,
        stepHistoryDegraded: Boolean = false,
        runStartedAt: Long? = null,
        nowMillis: () -> Long = { START_MILLIS },
    ): AgentRunBudget = AgentRunBudget(
        maxRunToolSteps = 2,
        maxObservationDecisions = 2,
        profilesByRunId = ConcurrentHashMap(),
        toolRequestsFor = { toolRequests },
        observationDecidedCount = { observationDecisions },
        sessionPlanStore = null,
        stepHistoryDegraded = { stepHistoryDegraded },
        runStartedAtMillis = { runStartedAt },
        nowMillis = nowMillis,
    )

    private fun request(id: String): ToolRequest =
        ToolRequest(
            id = id,
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            reason = "budget test",
        )

    private companion object {
        const val RUN_ID = "run-budget"
        const val START_MILLIS = 1_000_000L
    }
}

package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.tool.DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the relationship between the UI-action watchdog and the outer tool-execution boundary.
 *
 * These two budgets live in different layers and were tuned independently, which is how the bug
 * this test guards got in: removing the watchdog's hard clamp fixed spurious timeouts on long
 * waits, but at the old 10s schema ceiling the watchdog reached ~19s while the whole tool call —
 * watchdog plus the preflight observes that run before the action — still had to finish inside the
 * 20s boundary. The failure was invisible because the existing assertions only checked that the
 * watchdog exceeded the request (a lower bound), never that it stayed under the boundary.
 */
class UiActionTimeoutBudgetTest {

    /**
     * Preflight work billed to the same boundary before the action's watchdog window opens.
     *
     * Spelled out rather than imported because the underlying constants are file-private in their
     * own modules, and widening production visibility just to let a test read them would be the
     * wrong trade. If either budget moves, this figure has to move with it — which is the point:
     * the numbers are supposed to be reviewed together.
     *
     * 2 × 3000ms observe budget (SCREEN_STATE_WALK_BUDGET_MILLIS, one per preflight)
     * + 1500ms foreground-readiness poll (6 attempts × 250ms in DeviceControlToolExecutor)
     */
    private val preflightBudgetMillis = 2 * 3_000L + 1_500L

    @Test
    fun theWatchdogAtTheSchemaCeilingStaysInsideTheToolExecutionBoundary() {
        val watchdog = uiActionHardTimeoutMillis(MAX_UI_ACTION_TIMEOUT_MILLIS)

        assertTrue(
            "watchdog ${watchdog}ms at the ceiling must stay under the " +
                "${DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS}ms tool boundary",
            watchdog < DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun theWatchdogPlusPreflightStaysInsideTheToolExecutionBoundary() {
        // The real budget consumer: a ui_tap with expectedPackageName runs
        // expectedForegroundPackagePreflight and dangerousUiActionPreflight (a full-tree observe
        // each, plus the readiness poll) before the action's own watchdog window opens.
        val worstCase = uiActionHardTimeoutMillis(MAX_UI_ACTION_TIMEOUT_MILLIS) + preflightBudgetMillis

        assertTrue(
            "watchdog + preflight ${worstCase}ms must stay under the " +
                "${DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS}ms tool boundary",
            worstCase < DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun theWatchdogStillOutlastsWhatTheActionItselfCanSpend() {
        // The other half of the invariant, kept here so tightening one bound cannot silently
        // reintroduce the spurious-timeout bug the watchdog change originally fixed.
        for (requested in listOf(MIN_UI_ACTION_TIMEOUT_MILLIS, 1_000L, 3_000L, MAX_UI_ACTION_TIMEOUT_MILLIS)) {
            val watchdog = uiActionHardTimeoutMillis(requested)
            assertTrue(
                "watchdog ${watchdog}ms must exceed the requested ${requested}ms wait",
                watchdog > requested,
            )
        }
    }

    @Test
    fun requestsAboveTheCeilingAreClampedRatherThanHonoured() {
        assertTrue(
            "an out-of-schema request must not push the watchdog past the ceiling's value",
            uiActionHardTimeoutMillis(60_000L) == uiActionHardTimeoutMillis(MAX_UI_ACTION_TIMEOUT_MILLIS),
        )
    }
}

package com.bytedance.zgx.solin.eval

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.orchestration.AgentStep
import com.bytedance.zgx.solin.orchestration.ModelRuntimeInvocation
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.testBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The output-quality guard records its own RunDataReceipt so the stop reason stays auditable. That
 * receipt is not half of a (receipt, invocation) dispatch pair, so counting it as one made the
 * dispatch trace odd and broke the pairing contract these tests pin down.
 */
class OutputQualityReceiptPlacementTraceTest {
    private val runId = "run-quality"

    @Test
    fun guardReceiptDoesNotBreakDispatchPairing() {
        val steps = dispatchSteps() + AgentStep.RunDataReceiptRecorded(guardReceipt())

        val trace = steps.reconcilePlacementTrace(runId)

        assertNotNull("guard receipt must not invalidate the placement trace", trace)
        assertEquals(RunPlacement.Local, trace!!.placement)
    }

    @Test
    fun dispatchReceiptStillCountsWithoutTheGuard() {
        val trace = dispatchSteps().reconcilePlacementTrace(runId)

        assertNotNull(trace)
        assertEquals(RunPlacement.Local, trace!!.placement)
    }

    @Test
    fun twoDispatchesStillPairIndependentlyOfGuardReceipts() {
        val steps = listOf(
            AgentStep.PlacementSelected(testBinding(runId = runId, placement = RunPlacement.Local)),
            AgentStep.RunDataReceiptRecorded(dispatchReceipt()),
            AgentStep.ModelRuntimeInvocationStarted(invocation(attempt = 1)),
            AgentStep.RunDataReceiptRecorded(guardReceipt()),
            AgentStep.RunDataReceiptRecorded(dispatchReceipt()),
            AgentStep.ModelRuntimeInvocationStarted(invocation(attempt = 2)),
        )

        val trace = steps.reconcilePlacementTrace(runId)

        assertNotNull(trace)
        assertEquals(RunPlacement.Local, trace!!.placement)
    }

    private fun dispatchSteps(): List<AgentStep> = listOf(
        AgentStep.PlacementSelected(testBinding(runId = runId, placement = RunPlacement.Local)),
        AgentStep.RunDataReceiptRecorded(dispatchReceipt()),
        AgentStep.ModelRuntimeInvocationStarted(invocation(attempt = 1)),
    )

    private fun invocation(attempt: Int) = ModelRuntimeInvocation(
        runId = runId,
        placement = RunPlacement.Local,
        attempt = attempt,
        remoteProfileRevision = null,
    )

    private fun dispatchReceipt() = RunDataReceipt(
        destination = RunDataDestination.Local,
        currentPromptPrivacy = MessagePrivacy.LocalOnly.name,
    )

    /** Shaped like the receipt withOutputQualityDecision produces when the guard stops a run. */
    private fun guardReceipt() = RunDataReceipt(
        destination = RunDataDestination.Local,
        currentPromptPrivacy = MessagePrivacy.LocalOnly.name,
        outputQualityGuardTriggered = true,
        outputQualityIssue = "RepetitionLoop",
        outputQualityRule = "same_character_run>=24",
        outputQualityStopped = true,
    )
}

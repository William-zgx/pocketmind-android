package com.bytedance.zgx.solin.eval

import com.bytedance.zgx.solin.MessagePrivacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridges the JSONL golden corpus to the safety-invariant diff engine.
 *
 * The projector + [diffAgainst] were built and unit-tested, but nothing exercised them over the
 * committed fixture corpus loaded through the production deserializer ([AgentBehaviorEvalCase.fromJson]).
 * This test closes that gap so the confirmation/privacy/fail-closed boundary becomes an executable
 * regression gate over the real dataset:
 *
 * - Every golden case, paired with an "ideal" actual trace that reproduces its expected safety
 *   fields, must satisfy [AgentBehaviorPlanningTraceDiff.safetyBoundaryMatches] and
 *   [AgentBehaviorPlanningTraceDiff.failClosedInvariantMatches].
 * - Injecting a privacy/risk drift into the actual trace must flip the diff to a non-safe status,
 *   proving the gate actually detects boundary regressions rather than passing vacuously.
 */
class AiBehaviorGoldenDatasetGateTest {
    private val categories = listOf(
        "memory_recall",
        "planner_false_positive",
        "tool_sequence",
        "ocr_noise",
        "runtime_failure",
        "privacy_boundary",
        "restart_recovery",
    )

    @Test
    fun goldenCasesSatisfySafetyAndFailClosedInvariantsAgainstIdealTrace() {
        val cases = loadAllCases()
        assertTrue("expected a non-empty golden corpus", cases.isNotEmpty())

        cases.forEach { case ->
            val ideal = idealTraceFor(case)
            val diff = case.diffAgainst(ideal)
            assertTrue(
                "safety boundary must hold for golden case ${case.id}",
                diff.safetyBoundaryMatches,
            )
            assertTrue(
                "fail-closed invariant must hold for golden case ${case.id}",
                diff.failClosedInvariantMatches,
            )
        }
    }

    @Test
    fun privacyDriftInActualTraceIsRejected() {
        // Pick a LocalOnly golden case and prove that flipping the actual trace to remote-eligible
        // is caught as a safety-boundary violation — the gate is not vacuous.
        val localOnlyCase = loadAllCases().firstOrNull { it.localOnly }
            ?: error("expected at least one LocalOnly golden case")

        val drifted = idealTraceFor(localOnlyCase).copy(
            privacy = MessagePrivacy.RemoteEligible,
            localOnly = false,
            remoteEligible = true,
        )
        val diff = localOnlyCase.diffAgainst(drifted)

        assertTrue(
            "privacy drift must break the safety boundary for ${localOnlyCase.id}",
            !diff.safetyBoundaryMatches,
        )
        assertEquals(
            "privacy drift on a LocalOnly case must be a Mismatch",
            AgentBehaviorTraceDiffStatus.Mismatch,
            diff.status,
        )
    }

    /**
     * An actual trace that faithfully reproduces the case's expected safety-relevant fields. This is
     * the "the runtime did exactly what the contract asked" baseline; real actual traces are produced
     * by [AgentBehaviorTraceProjector] in AiBehaviorActualTraceGeneratorTest. Placement/routing are
     * copied through only when the case declares them so the diff status stays well-defined.
     */
    private fun idealTraceFor(case: AgentBehaviorEvalCase): AgentBehaviorActualTrace =
        AgentBehaviorActualTrace(
            caseId = case.id,
            input = case.input,
            actualTools = case.expectedTools,
            actualConfirmation = case.expectedConfirmation,
            actualRiskLevel = case.expectedRiskLevel,
            privacy = case.privacy,
            localOnly = case.localOnly,
            remoteEligible = case.remoteEligible,
            failureMode = case.allowedFailureModes.firstOrNull(),
            routingPath = case.expectedRoutingPath,
            routingToolName = case.expectedRoutingToolName,
            routingSkillId = case.expectedRoutingSkillId,
            routingRejectionReason = case.expectedRoutingRejectionReason,
            actualPlacement = case.expectedPlacement,
            actualPlacementReason = case.expectedPlacementReason,
        )

    private fun loadAllCases(): List<AgentBehaviorEvalCase> =
        categories.flatMap { category ->
            loadFixtureRows("$category.jsonl").map(AgentBehaviorEvalCase::fromJson)
        }

    private fun loadFixtureRows(fileName: String): List<JSONObject> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("ai_behavior_eval/$fileName")
            ?: error("Missing fixture $fileName")
        return stream.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map(::JSONObject)
                .toList()
        }
    }
}

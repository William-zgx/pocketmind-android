package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVisionObservationReplannerTest {
    private fun context(
        priorRequests: List<ToolRequest> = emptyList(),
        previousArguments: Map<String, String> = emptyMap(),
        inheritedPackage: String? = null,
        status: ToolStatus = ToolStatus.Succeeded,
        runHasUserConfirmedStep: Boolean = true,
    ): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "obs-1",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = previousArguments,
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-1",
                input = "帮我在小红书搜索并关注博主",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = status,
                summary = "已观察当前屏幕。",
                data = mapOf("toolName" to previousRequest.toolName),
            ),
            priorRequests = priorRequests + previousRequest,
            inheritedExpectedForegroundPackage = inheritedPackage,
            runHasUserConfirmedStep = runHasUserConfirmedStep,
        )
    }

    @Test
    fun returnsNullWhenGateOff() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> error("decider must not be called when gate is off") },
            gateProvider = { false },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context()))
    }

    @Test
    fun failsClosedWhenRunNotYetUserConfirmed() {
        // First-confirm-then-auto: before the user has confirmed this run's first action, the
        // decider (which captures + sends the screenshot off-device) must never be called.
        var deciderCalls = 0
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
            gateProvider = { true },
            executeDecisions = true,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context(runHasUserConfirmedStep = false)))
        assertEquals(0, deciderCalls)
        assertTrue(logs.any { it.contains("not yet user-confirmed") })
    }

    @Test
    fun readOnlyModeLogsTapButDoesNotReplan() {
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
            gateProvider = { true },
            executeDecisions = false,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context()))
        assertTrue(logs.any { it.contains("decision tap (500,250)") })
        assertTrue(logs.any { it.contains("read-only") })
    }

    @Test
    fun executeModeTurnsTapIntoLocalUiTapReplan() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(context())
        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("500", replan?.request?.arguments?.get("targetX"))
        assertEquals("250", replan?.request?.arguments?.get("targetY"))
        assertTrue(replan?.plannedByModel == true)
        // Reason must match the shared model-observation replan reason so the per-run replan
        // counter counts remote-vision steps against the same budget.
        assertEquals(MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan?.request?.reason)
    }

    @Test
    fun tapInheritsExpectedForegroundPackageFromContext() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))
        assertEquals("com.xingin.xhs", replan?.request?.arguments?.get("expectedPackageName"))
    }

    @Test
    fun tapPrefersPreviousRequestExpectedPackageOverInherited() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(
            context(
                previousArguments = mapOf("expectedPackageName" to "com.from.request"),
                inheritedPackage = "com.from.context",
            ),
        )
        assertEquals("com.from.request", replan?.request?.arguments?.get("expectedPackageName"))
    }

    @Test
    fun stopDecisionReturnsNull() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Stop },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context()))
    }

    @Test
    fun unavailableDecisionFailsClosedToNull() {
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Unavailable("missing_consent") },
            gateProvider = { true },
            executeDecisions = true,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context()))
        assertTrue(logs.any { it.contains("unavailable (missing_consent)") })
    }

    @Test
    fun stopsWhenReplanLimitReached() {
        var deciderCalls = 0
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
            gateProvider = { true },
            executeDecisions = true,
            maxReplans = 2,
            logger = {},
        )
        // Two prior remote-vision replans already recorded → limit reached, decider not consulted.
        val priorReplans = List(2) {
            ToolRequest(toolName = MobileActionFunctions.UI_TAP, reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON)
        }
        assertNull(replanner.planNext(context(priorRequests = priorReplans)))
        assertEquals(0, deciderCalls)
    }

    @Test
    fun ignoresNonReplannableObservation() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> error("decider must not be called for rejected observation") },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context(status = ToolStatus.Rejected)))
    }

    @Test
    fun parserReadsTapAndStop() {
        assertEquals(
            RemoteVisionDecision.Tap(500, 250),
            parseRemoteVisionDecision("""{"action":"tap","x":500,"y":250}"""),
        )
        assertEquals(RemoteVisionDecision.Stop, parseRemoteVisionDecision("""{"action":"stop"}"""))
    }

    @Test
    fun parserToleratesSurroundingProse() {
        assertEquals(
            RemoteVisionDecision.Tap(300, 400),
            parseRemoteVisionDecision("""好的，我的决策是：{"action":"tap","x":300,"y":400}。"""),
        )
    }

    @Test
    fun parserFailsClosedOnOutOfRangeOrUnparseable() {
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","x":1500,"y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","x":-5,"y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("no json here") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"frobnicate"}""") is RemoteVisionDecision.Unavailable)
    }

    @Test
    fun parserReadsStringCoordinates() {
        assertEquals(
            RemoteVisionDecision.Tap(500, 250),
            parseRemoteVisionDecision("""{"action":"tap","x":"500","y":"250"}"""),
        )
    }
}

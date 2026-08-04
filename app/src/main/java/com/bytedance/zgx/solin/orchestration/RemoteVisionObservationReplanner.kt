package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.tool.ToolRequest

private const val REMOTE_VISION_REPLAN_REQUEST_REASON = MODEL_OBSERVATION_REPLAN_REQUEST_REASON
private const val REMOTE_VISION_REPLAN_FALLBACK_REASON = "remote vision observation replan"
private const val DEFAULT_MAX_REMOTE_VISION_REPLANS = 5

/**
 * A single tap/stop decision the remote vision model returns after "seeing" the current screenshot.
 *
 * The model's output is an UNTRUSTED suggestion — the same trust posture as the local action model,
 * just a different backend. It is parsed into normalized 0-1000 coordinates and turned into a LOCAL
 * `ui_tap` draft that re-enters the normal plan→confirm→execute path with all device-control
 * preflights intact (dangerous-action, foreground-package). The model never receives a tool schema
 * and never emits a tool_call.
 */
sealed interface RemoteVisionDecision {
    /** Tap the given normalized 0-1000 coordinate. */
    data class Tap(val normalizedX: Int, val normalizedY: Int) : RemoteVisionDecision

    /** No further action — the task is complete, or the model declined (e.g. a dangerous control). */
    data object Stop : RemoteVisionDecision

    /**
     * The decision could not be produced this step (no consent, capture failed, vision send failed,
     * unparseable output). Fail closed: the loop stops rather than guessing. [reason] is a
     * non-sensitive diagnostic label for logs/audit only.
     */
    data class Unavailable(val reason: String) : RemoteVisionDecision
}

/**
 * Captures the current screen, sends the screenshot to the remote vision model, and parses its
 * tap/stop decision. This is the boundary-crossing, coroutine-blocking, Android-dependent half of
 * the remote-vision loop — isolated behind an interface so [RemoteVisionObservationReplanner]'s
 * gating and ordering logic stays pure and unit-testable with a fake.
 *
 * Implementations MUST fail closed: return [RemoteVisionDecision.Unavailable] (never throw, never
 * a default Tap) when consent is missing, capture fails, the send fails, or output is unparseable.
 */
fun interface RemoteVisionDecider {
    /**
     * @param intent the user's request preview (for the decision prompt)
     * @param requestId a correlation/log id for this capture (the accessibility capture path needs
     *   no per-request consent; egress is gated by the opt-in + first-confirm, not by requestId)
     */
    fun decide(intent: String, requestId: String): RemoteVisionDecision
}

/**
 * Drives in-app GUI automation with a remote vision model (e.g. kimi-k2.5) instead of the local
 * action model. Occupies the same [AgentObservationReplanner] slot as [ModelObservationReplanner]
 * in the [CompositeAgentObservationReplanner], inserted AFTER dead-loop detection and BEFORE the
 * local model replanner: when remote-GUI driving is active it outranks the local 270M/e2b model;
 * when the gate is off it returns null so the local model still runs in Local/Auto mode.
 *
 * Unlike the RemoteToolScope path, the remote model here is a pure perception+decision oracle: it
 * sees a screenshot and returns a tap coordinate, which becomes a LOCAL `ui_tap` executed through
 * the unchanged device-control executor. See [com.bytedance.zgx.solin.multimodal.RemoteVisionScreenshotContract].
 *
 * @param decider the capture→send→parse seam (Android/coroutine/network isolated behind it)
 * @param gateProvider true only when inferenceMode==Remote AND the remote-GUI opt-in is on AND the
 *   remote config declares vision support. Returns null (local model takes over) when false.
 * @param executeDecisions when false (Phase 2, read-only), a parsed Tap is logged but NOT turned
 *   into a replan (returns null) — used to validate perception + gate ordering without touching the
 *   device. When true (Phase 3+), a Tap becomes a real `ui_tap` replan.
 */
class RemoteVisionObservationReplanner(
    private val decider: RemoteVisionDecider,
    private val gateProvider: () -> Boolean,
    private val executeDecisions: Boolean = false,
    maxReplans: Int = DEFAULT_MAX_REMOTE_VISION_REPLANS,
    private val logger: (String) -> Unit = { message -> solinD(TAG_REMOTE, message) },
) : AgentObservationReplanner {
    private val replanLimit = maxReplans.coerceAtLeast(0)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!gateProvider()) return null
        if (replanLimit == 0) return null
        if (!context.observedResult.isModelReplannableObservation()) return null
        // First-confirm-then-auto: the screenshot must not leave the device until the user has
        // confirmed this run's first action. The decider performs the capture + remote send, so we
        // gate BEFORE calling it. Once confirmed, subsequent captures in the same run auto-proceed
        // (bounded by replanLimit); a new run/session starts unconfirmed again (fail closed).
        if (!context.runHasUserConfirmedStep) {
            logger("remote-vision: run not yet user-confirmed — no screenshot sent (fail closed)")
            return null
        }
        if (context.modelObservationReplanCount() >= replanLimit) {
            logger("remote-vision: replan limit ($replanLimit) reached")
            return null
        }
        val intent = (context.nextActionInput?.immediateSequentialActionText() ?: context.run.input)
        val decision = decider.decide(intent = intent, requestId = context.previousRequest.id)
        return when (decision) {
            is RemoteVisionDecision.Tap -> {
                logger("remote-vision: decision tap (${decision.normalizedX},${decision.normalizedY})")
                if (!executeDecisions) {
                    logger("remote-vision: read-only mode — tap not executed")
                    null
                } else {
                    context.toTapReplan(decision)
                }
            }

            RemoteVisionDecision.Stop -> {
                logger("remote-vision: decision stop")
                null
            }

            is RemoteVisionDecision.Unavailable -> {
                logger("remote-vision: unavailable (${decision.reason}) — fail closed, loop stops")
                null
            }
        }
    }

    /**
     * Builds a LOCAL `ui_tap` request from the remote model's normalized coordinates. Propagates the
     * inherited expected-foreground-package exactly as [ModelObservationReplanner] does, so the tap's
     * foreground-package preflight fires on a REMOTE-planned continuation where open_app_by_name
     * carried only appName. The model's decision is untrusted; the local executor still runs the
     * dangerous-action and foreground preflights before the tap.
     */
    private fun AgentObservationReplanContext.toTapReplan(
        decision: RemoteVisionDecision.Tap,
    ): AgentObservationReplan {
        val arguments = buildMap {
            put("targetX", decision.normalizedX.toString())
            put("targetY", decision.normalizedY.toString())
            val inheritedPackage = previousRequest.arguments["expectedPackageName"]?.trim()?.takeIf { it.isNotBlank() }
                ?: previousRequest.arguments["targetPackageName"]?.trim()?.takeIf { it.isNotBlank() }
                ?: inheritedExpectedForegroundPackage
            if (inheritedPackage != null) put("expectedPackageName", inheritedPackage)
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_TAP,
            title = "点击坐标",
            summary = "远程视觉模型规划点击坐标 (${decision.normalizedX}, ${decision.normalizedY})。",
            parameters = arguments,
        )
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_TAP,
                arguments = arguments,
                reason = REMOTE_VISION_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = true,
            fallbackReason = REMOTE_VISION_REPLAN_FALLBACK_REASON,
        )
    }
}

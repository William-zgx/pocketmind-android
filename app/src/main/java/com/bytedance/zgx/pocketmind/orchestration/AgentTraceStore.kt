package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

data class AgentTraceStepSummary(
    val runId: String,
    val position: Int,
    val type: String,
    val summary: String,
    val json: String,
    val createdAtMillis: Long,
)

interface AgentTraceStore {
    fun createRun(input: String): AgentRun
    fun run(runId: String): AgentRun?
    fun latestNonFinalRun(): AgentRun?
    fun updateState(runId: String, state: AgentRunState): AgentRun
    fun appendStep(runId: String, step: AgentStep)
    fun steps(runId: String): List<AgentStep>
    fun stepSummaries(runId: String): List<AgentTraceStepSummary>
    fun latestPendingToolRequest(runId: String): RecoverableToolRequest?
}

class InMemoryAgentTraceStore(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AgentTraceStore {
    private val nextRunId = AtomicLong(0L)
    private val runs = linkedMapOf<String, AgentRun>()
    private val runSteps = linkedMapOf<String, MutableList<AgentStep>>()
    private val runStepSummaries = linkedMapOf<String, MutableList<AgentTraceStepSummary>>()

    override fun createRun(input: String): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = "run-${nextRunId.incrementAndGet()}",
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        runs[run.id] = run
        runSteps[run.id] = mutableListOf()
        runStepSummaries[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        runs[runId]

    override fun latestNonFinalRun(): AgentRun? =
        runs.values
            .filter { it.state !in FINAL_AGENT_RUN_STATES }
            .maxByOrNull { it.updatedAtMillis }

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val existing = runs.getValue(runId)
        val updated = existing.copy(
            state = state,
            updatedAtMillis = clockMillis(),
        )
        runs[runId] = updated
        return updated
    }

    override fun appendStep(runId: String, step: AgentStep) {
        val steps = runSteps.getValue(runId)
        runStepSummaries.getValue(runId).add(
            step.toTraceStepSummary(
                runId = runId,
                position = steps.size,
                createdAtMillis = clockMillis(),
            ),
        )
        steps.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        runSteps[runId].orEmpty().toList()

    override fun latestPendingToolRequest(runId: String): RecoverableToolRequest? {
        val allSteps = steps(runId)
        val confirmationRequestId = allSteps
            .asReversed()
            .mapNotNull { step -> (step as? AgentStep.UserConfirmationRequested)?.request?.id }
            .firstOrNull() ?: return null
        val requestStep = allSteps
            .asReversed()
            .mapNotNull { step -> step as? AgentStep.ToolRequested }
            .firstOrNull { step -> step.request.id == confirmationRequestId } ?: return null
        return RecoverableToolRequest(
            request = requestStep.request,
            draftTitle = requestStep.draft.title.ifBlank { requestStep.request.toolName },
            draftSummary = requestStep.draft.summary,
            hasFullArguments = true,
        )
    }

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        runStepSummaries[runId].orEmpty().toList()
}

class RoomAgentTraceStore(
    private val traceDao: AgentTraceDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val runIdFactory: () -> String = { "run-${UUID.randomUUID()}" },
) : AgentTraceStore {
    private val liveSteps = linkedMapOf<String, MutableList<AgentStep>>()

    override fun createRun(input: String): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = runIdFactory(),
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        traceDao.upsertRun(run.toEntity())
        liveSteps[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        traceDao.run(runId)?.toDomain()

    override fun latestNonFinalRun(): AgentRun? =
        traceDao.recentRuns(10)
            .mapNotNull { runEntity ->
                runCatching { runEntity.toDomain() }
                    .getOrNull()
            }
            .firstOrNull { it.state !in FINAL_AGENT_RUN_STATES }

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val now = clockMillis()
        val updatedRows = traceDao.updateRunState(runId, state.name, now)
        require(updatedRows > 0) { "Agent run does not exist: $runId" }
        return traceDao.run(runId)?.toDomain()
            ?: error("Agent run disappeared after update: $runId")
    }

    override fun appendStep(runId: String, step: AgentStep) {
        requireNotNull(traceDao.run(runId)) { "Agent run does not exist: $runId" }
        val now = clockMillis()
        val position = traceDao.nextStepPosition(runId)
        traceDao.insertStep(step.toEntity(runId, position, now))
        traceDao.touchRun(runId, now)
        liveSteps.getOrPut(runId) { mutableListOf() }.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        liveSteps[runId]?.toList() ?: traceDao.steps(runId).mapNotNull { entity ->
            entity.toSummary().toRecoveredStep()
        }

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        traceDao.steps(runId).map { entity -> entity.toSummary() }

    override fun latestPendingToolRequest(runId: String): RecoverableToolRequest? {
        val confirmationRequestId = stepSummaries(runId)
            .asReversed()
            .mapNotNull { it.toPendingToolRequestId() }
            .firstOrNull() ?: return null
        return stepSummaries(runId).firstOrNull { summary ->
            summary.requestId() == confirmationRequestId
        }?.toRecoverableToolRequest()
    }

private fun AgentTraceStepSummary.requestId(): String =
    runCatching { JSONObject(json).optString("requestId") }
            .getOrDefault("")
}

private fun AgentRun.toEntity(): AgentRunEntity =
    AgentRunEntity(
        id = id,
        input = input,
        state = state.name,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

private fun AgentRunEntity.toDomain(): AgentRun =
    AgentRun(
        id = id,
        input = input,
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.Failed),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

private fun AgentStep.toEntity(runId: String, position: Int, createdAtMillis: Long): AgentStepEntity {
    val summary = toTraceStepSummary(runId, position, createdAtMillis)
    return AgentStepEntity(
        runId = summary.runId,
        position = summary.position,
        type = summary.type,
        summary = summary.summary,
        json = summary.json,
        createdAtMillis = summary.createdAtMillis,
    )
}

private fun AgentStep.toTraceStepSummary(
    runId: String,
    position: Int,
    createdAtMillis: Long,
): AgentTraceStepSummary {
    val type = traceType()
    return AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = traceSummary(),
        json = traceJson(type).toString(),
        createdAtMillis = createdAtMillis,
    )
}

private fun AgentTraceStepSummary.toPendingToolRequestId(): String? {
    if (type != "UserConfirmationRequested") return null
    return runCatching {
        JSONObject(json).optString("requestId")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun AgentTraceStepSummary.toRecoverableToolRequest(): RecoverableToolRequest? {
    if (type != "ToolRequested") return null
    val summary = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val requestId = summary.optString("requestId")
    if (requestId.isBlank()) return null
    val toolName = summary.optString("toolName").ifBlank { "unknown_tool" }
    val reason = summary.optString("reason")
    val draftTitle = summary.optString("draftTitle").ifBlank { toolName }
    val argumentKeys = summary
        .optJSONArray("argumentKeys")
        ?.run {
            (0 until length()).mapNotNull { index -> getString(index).takeIf { it.isNotBlank() } }
        } ?: emptyList()
    return RecoverableToolRequest(
        request = ToolRequest(
            id = requestId,
            toolName = toolName,
            arguments = if (argumentKeys.isEmpty()) emptyMap() else argumentKeys.associateWith { "" },
            reason = reason,
        ),
        draftTitle = draftTitle,
        draftSummary = reason,
        hasFullArguments = argumentKeys.isEmpty(),
    )
}

private fun AgentTraceStepSummary.toRecoveredStep(): AgentStep? {
    val type = type
    val summary = runCatching { JSONObject(json) }.getOrNull() ?: return null
    return when (type) {
        "ContextLoaded" -> AgentStep.ContextLoaded(
            memoryHits = emptyList(),
            deviceContext = null,
        )
        "ModelPlanned" -> null
        "ToolRequested" -> {
            val requestId = summary.optString("requestId")
            val toolName = summary.optString("toolName")
            val argumentKeys = summary.optJSONArray("argumentKeys")?.toStringList() ?: emptyList()
            val reason = summary.optString("reason")
            AgentStep.ToolRequested(
                request = ToolRequest(
                    id = requestId,
                    toolName = toolName,
                    arguments = argumentKeys.associateWith { "" },
                    reason = reason,
                ),
                draft = ActionDraft(
                    functionName = toolName,
                    title = summary.optString("draftTitle").ifBlank { toolName },
                    summary = reason.ifBlank { "工具请求" },
                    parameters = argumentKeys.associateWith { "" },
                    requiresConfirmation = true,
                ),
            )
        }
        "SkillPlanned", "SafetyChecked", "UserConfirmationRequested", "UserConfirmed", "UserRejected" -> null
        "ObservationDecided", "AssistantResponded", "ToolObserved", "ToolRetryScheduled", "ToolRejected" -> null
        "Failed" -> null
        else -> null
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }

private val FINAL_AGENT_RUN_STATES: Set<AgentRunState> = setOf(
    AgentRunState.Completed,
    AgentRunState.Cancelled,
    AgentRunState.Failed,
)

private fun AgentStepEntity.toSummary(): AgentTraceStepSummary =
    AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = summary,
        json = json,
        createdAtMillis = createdAtMillis,
    )

private fun AgentStep.traceType(): String =
    when (this) {
        is AgentStep.ContextLoaded -> "ContextLoaded"
        is AgentStep.ModelPlanned -> "ModelPlanned"
        is AgentStep.ToolRequested -> "ToolRequested"
        is AgentStep.SkillPlanned -> "SkillPlanned"
        is AgentStep.SafetyChecked -> "SafetyChecked"
        is AgentStep.UserConfirmationRequested -> "UserConfirmationRequested"
        is AgentStep.UserConfirmed -> "UserConfirmed"
        is AgentStep.UserRejected -> "UserRejected"
        is AgentStep.ToolObserved -> "ToolObserved"
        is AgentStep.ToolRetryScheduled -> "ToolRetryScheduled"
        is AgentStep.ObservationDecided -> "ObservationDecided"
        is AgentStep.AssistantResponded -> "AssistantResponded"
        is AgentStep.ToolRejected -> "ToolRejected"
        is AgentStep.Failed -> "Failed"
    }

private fun AgentStep.traceSummary(): String =
    when (this) {
        is AgentStep.ContextLoaded -> {
            val deviceContext = if (deviceContext == null) "without device context" else "with device context"
            "Loaded ${memoryHits.size} memory hit(s) $deviceContext."
        }

        is AgentStep.ModelPlanned -> plan.traceSummary()
        is AgentStep.ToolRequested -> "Requested tool ${request.toolName}: ${request.reason.shortTraceText()}"
        is AgentStep.SkillPlanned -> "Planned skill ${request.skillId} with ${plan?.steps?.size ?: 0} step(s)."
        is AgentStep.SafetyChecked -> "Safety ${decision.outcome}: ${decision.reason.shortTraceText()}"
        is AgentStep.UserConfirmationRequested -> "Requested confirmation for ${request.toolName}."
        is AgentStep.UserConfirmed -> "User confirmed request $requestId."
        is AgentStep.UserRejected -> "User rejected request $requestId."
        is AgentStep.ToolObserved -> "Observed ${result.status} for ${result.requestId}: ${result.summary.shortTraceText()}"
        is AgentStep.ToolRetryScheduled -> "Retry $attempt scheduled for ${request.toolName}: ${reason.shortTraceText()}"
        is AgentStep.ObservationDecided -> decision.traceSummary()
        is AgentStep.AssistantResponded -> "Assistant responded: ${text.shortTraceText()}"
        is AgentStep.ToolRejected -> "Rejected tool result ${result.requestId}: ${result.summary.shortTraceText()}"
        is AgentStep.Failed -> "Failed: ${reason.shortTraceText()}"
    }

private fun AgentPlan.traceSummary(): String =
    when (this) {
        is AgentPlan.Answer -> "Planned answer generation."
        is AgentPlan.UseTool -> "Planned tool ${request.toolName}: ${request.reason.shortTraceText()}"
        is AgentPlan.RejectedTool -> "Rejected tool plan: ${result.summary.shortTraceText()}"
        is AgentPlan.MissingModel -> "Missing model capability ${capability.name}."
    }

private fun AgentObservationDecision.traceSummary(): String =
    when (this) {
        AgentObservationDecision.Complete -> "Observation complete."
        is AgentObservationDecision.ContinueWithModel ->
            "Continue with model: ${reason.shortTraceText()}"
        is AgentObservationDecision.RetryTool ->
            "Retry ${request.toolName} attempt $attempt: ${reason.shortTraceText()}"
        is AgentObservationDecision.PlanNextTool ->
            "Plan next tool ${plan.request.toolName}: ${reason.shortTraceText()}"
        is AgentObservationDecision.Fail -> "Observation failed: ${reason.shortTraceText()}"
        AgentObservationDecision.Cancel -> "Observation cancelled."
    }

private fun AgentStep.traceJson(type: String): JSONObject {
    val json = JSONObject().put("type", type)
    when (this) {
        is AgentStep.ContextLoaded -> json
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentStep.ModelPlanned -> json.put("plan", plan.traceJson())
        is AgentStep.ToolRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("reason", request.reason.shortTraceText())
            .put("argumentKeys", request.arguments.keys.sorted())
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.SkillPlanned -> json
            .put("skillRequestId", request.id)
            .put("skillId", request.skillId)
            .put("stepCount", plan?.steps?.size ?: 0)

        is AgentStep.SafetyChecked -> json
            .put("outcome", decision.outcome.name)
            .put("reason", decision.reason.shortTraceText())

        is AgentStep.UserConfirmationRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.UserConfirmed -> json.put("requestId", requestId)
        is AgentStep.UserRejected -> json.put("requestId", requestId)
        is AgentStep.ToolObserved -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("summary", result.summary.shortTraceText())
            .put("retryable", result.retryable)

        is AgentStep.ToolRetryScheduled -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)
            .put("reason", reason.shortTraceText())

        is AgentStep.ObservationDecided -> json.put("decision", decision.traceJson())
        is AgentStep.AssistantResponded -> json
            .put("textPreview", text.shortTraceText())
            .put("textLength", text.length)

        is AgentStep.ToolRejected -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("summary", result.summary.shortTraceText())

        is AgentStep.Failed -> json.put("reason", reason.shortTraceText())
    }
    return json
}

private fun AgentPlan.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        is AgentPlan.Answer -> json
            .put("type", "Answer")
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentPlan.UseTool -> json
            .put("type", "UseTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("plannedByModel", plannedByModel)
            .put("skillId", skillRequest?.skillId)
            .put("fallbackReason", fallbackReason?.shortTraceText())
            .put("safetyOutcome", safetyDecision.outcome.name)

        is AgentPlan.RejectedTool -> json
            .put("type", "RejectedTool")
            .put("requestId", result.requestId)
            .put("summary", result.summary.shortTraceText())

        is AgentPlan.MissingModel -> json
            .put("type", "MissingModel")
            .put("capability", capability.name)
    }
    return json
}

private fun AgentObservationDecision.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        AgentObservationDecision.Complete -> json.put("type", "Complete")
        is AgentObservationDecision.ContinueWithModel -> json
            .put("type", "ContinueWithModel")
            .put("requiresLocalModel", requiresLocalModel)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.RetryTool -> json
            .put("type", "RetryTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.PlanNextTool -> json
            .put("type", "PlanNextTool")
            .put("requestId", plan.request.id)
            .put("toolName", plan.request.toolName)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.Fail -> json
            .put("type", "Fail")
            .put("reason", reason.shortTraceText())

        AgentObservationDecision.Cancel -> json.put("type", "Cancel")
    }
    return json
}

private fun String.shortTraceText(maxLength: Int = 160): String {
    val compact = lineSequence()
        .joinToString(" ") { line -> line.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength - 3) + "..."
}

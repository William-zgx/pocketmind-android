package com.bytedance.zgx.solin.orchestration

data class AgentRunOptions(
    val initialPlanningMode: InitialPlanningMode = InitialPlanningMode.RuleFirst,
    val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val reduceDeviceActionConfirmations: Boolean = false,
    /**
     * When true, the in-app observe→replan→tap loop is enabled without a local action model: the
     * remote vision model ([RemoteVisionObservationReplanner]) drives GUI automation instead. Set by
     * ChatController when inferenceMode==Remote AND the remote-GUI opt-in is on AND the remote config
     * declares vision support. OR-ed into `hasMobileActionPlanningModel` so the loop's fail-closed
     * MissingModel gate passes on the remote-vision path.
     */
    val remoteGuiDrivingEnabled: Boolean = false,
    val profile: AgentProfile = AgentProfile.DEFAULT,
) {
    /**
     * Whether this run is set up to plan and call tools rather than answer in one shot. Used to
     * size the request before a placement is chosen, so it must be answerable up front — it
     * reports intent, not whether a tool actually ran.
     */
    fun expectsToolLoop(): Boolean =
        initialPlanningMode == InitialPlanningMode.ModelFirstRemoteTools ||
            remoteToolScope == RemoteToolScope.ModelPlanning ||
            remoteGuiDrivingEnabled
}

enum class InitialPlanningMode {
    RuleFirst,
    ModelFirstRemoteTools,
}

enum class RemoteToolScope {
    PublicEvidenceOnly,
    ModelPlanning,
}

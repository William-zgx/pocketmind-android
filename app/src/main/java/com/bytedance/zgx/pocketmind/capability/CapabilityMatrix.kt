package com.bytedance.zgx.pocketmind.capability

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapability
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import com.bytedance.zgx.pocketmind.tool.isRemoteModelPlanningEligible

enum class CapabilityOwnerAgent {
    Coordinator,
    EdgeModel,
    Multimodal,
    AgentRuntime,
    Memory,
    TrustPrivacy,
    PerformanceQa,
}

enum class CapabilityPrivacyLevel {
    PublicEvidence,
    LocalEvidence,
    ExternalAction,
    BackgroundTask,
}

data class CapabilityDescriptor(
    val capabilityId: String,
    val entrypoint: String,
    val toolName: String?,
    val modelCapability: ModelCapability?,
    val privacyLevel: CapabilityPrivacyLevel,
    val requiresLocalModel: Boolean,
    val remoteEligible: Boolean,
    val confirmationPolicy: ConfirmationPolicy,
    val failureBehavior: String,
    val requiredTests: List<String>,
    val ownerAgent: CapabilityOwnerAgent,
)

object CapabilityMatrix {
    fun toolDescriptors(registry: ToolRegistry = ToolRegistry()): List<CapabilityDescriptor> =
        registry.specs().map { spec -> spec.toCapabilityDescriptor() }

    val productDescriptors: List<CapabilityDescriptor> =
        listOf(
            CapabilityDescriptor(
                capabilityId = "local_offline_chat",
                entrypoint = "chat_input",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = true,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "本地模型未就绪时停止生成并提示先准备模型。",
                requiredTests = listOf("PocketMindViewModelTest"),
                ownerAgent = CapabilityOwnerAgent.EdgeModel,
            ),
            CapabilityDescriptor(
                capabilityId = "explicit_memory",
                entrypoint = "remember_forget_commands",
                toolName = null,
                modelCapability = ModelCapability.MemoryEmbedding,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "删除失败时保留现有记忆，不把记忆写入远端请求。",
                requiredTests = listOf("MemoryRepositoryTest", "MemoryQualityContractTest"),
                ownerAgent = CapabilityOwnerAgent.Memory,
            ),
            CapabilityDescriptor(
                capabilityId = "remote_vision_image_input",
                entrypoint = "share_or_attachment_image",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.PublicEvidence,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "远程配置不支持图片输入时直接提示不支持，不强制 OCR。",
                requiredTests = listOf("RemoteChatRuntimeTest", "PocketMindViewModelTest"),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
        )

    fun allDescriptors(registry: ToolRegistry = ToolRegistry()): List<CapabilityDescriptor> =
        productDescriptors + toolDescriptors(registry)
}

private fun ToolSpec.toCapabilityDescriptor(): CapabilityDescriptor {
    val privacyLevel = when {
        resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence ->
            CapabilityPrivacyLevel.PublicEvidence

        resultContinuationPolicy == ToolResultContinuationPolicy.LocalEvidence ||
            privateOutputKeys.isNotEmpty() ->
            CapabilityPrivacyLevel.LocalEvidence

        capability == ToolCapability.BackgroundTask -> CapabilityPrivacyLevel.BackgroundTask
        else -> CapabilityPrivacyLevel.ExternalAction
    }
    return CapabilityDescriptor(
        capabilityId = "tool_$name",
        entrypoint = "tool_registry",
        toolName = name,
        modelCapability = ModelCapability.MobileAction,
        privacyLevel = privacyLevel,
        requiresLocalModel = privacyLevel == CapabilityPrivacyLevel.LocalEvidence,
        remoteEligible = isRemoteModelPlanningEligible(),
        confirmationPolicy = confirmationPolicy,
        failureBehavior = if (riskLevel == RiskLevel.LowReadOnly) {
            "返回失败摘要并阻止无效结果进入模型上下文。"
        } else {
            "拒绝、取消或权限失败时不执行外部动作。"
        },
        requiredTests = listOf("ToolSchemaContractTest", "SafetyPolicyTest", "AgentLoopRuntimeTest"),
        ownerAgent = CapabilityOwnerAgent.AgentRuntime,
    )
}

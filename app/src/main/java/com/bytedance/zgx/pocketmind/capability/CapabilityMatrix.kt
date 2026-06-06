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
    UserProvided,
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
                capabilityId = "shared_file_text_input",
                entrypoint = "share_or_document_picker",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "远程模式保护分享文本和非图片附件；本地模式只读取受限文本、OCR 或元数据。",
                requiredTests = listOf("SharedInputTest", "PocketMindViewModelTest", "MainActivitySharedIntentTest"),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "remote_vision_image_input",
                entrypoint = "share_or_attachment_image",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "远程配置不支持图片输入时直接提示不支持，不强制 OCR；远程视觉 prompt 不包含附件文件名、MIME、大小或 OCR。",
                requiredTests = listOf(
                    "RemoteChatRuntimeTest",
                    "PocketMindViewModelTest",
                    "MainActivitySharedInputModeTest",
                    "SharedInputTest",
                ),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "voice_transcript_input",
                entrypoint = "composer_voice_input",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "语音识别失败或权限拒绝时只更新本地状态，不读取音频文件或自动发送消息。",
                requiredTests = listOf("PocketMindViewModelTest", "MainActivitySmokeTest"),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "confirmed_device_tools",
                entrypoint = "agent_tool_confirmation",
                toolName = null,
                modelCapability = ModelCapability.MobileAction,
                privacyLevel = CapabilityPrivacyLevel.ExternalAction,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "中高风险、本地私密读取和外部动作必须先确认；取消、权限拒绝或批量混合风险时 fail-closed。",
                requiredTests = listOf("ToolRegistryTest", "SafetyPolicyTest", "AgentLoopRuntimeTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "auditable_agent_trace",
                entrypoint = "agent_trace_and_audit_surfaces",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "损坏、过期或越界的 pending/trace 数据在恢复时清理或标记失败，不恢复敏感 payload。",
                requiredTests = listOf("AgentTraceStoreTest", "RunDataReceiptTraceTest", "ToolAuditRepositoryTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "model_management",
                entrypoint = "model_manager",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "下载、导入、校验、加载或 fallback 失败时更新 ModelHealth，不把模型已下载误报为所有能力可用。",
                requiredTests = listOf("ModelCatalogTest", "PocketMindViewModelTest", "PocketMindScreenDisplayTest"),
                ownerAgent = CapabilityOwnerAgent.EdgeModel,
            ),
            CapabilityDescriptor(
                capabilityId = "run_data_receipt",
                entrypoint = "run_completion_receipt",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "回执只记录本地/远端边界、受保护来源和计数，不记录原始 prompt、文件内容或图片数据。",
                requiredTests = listOf("RunDataReceiptTraceTest", "PocketMindScreenDisplayTest"),
                ownerAgent = CapabilityOwnerAgent.TrustPrivacy,
            ),
            CapabilityDescriptor(
                capabilityId = "release_gate",
                entrypoint = "scripts/verify_release_gate.sh",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "缺少 perf baseline、签名、AAB、审批、验证记录或 artifact 绑定时 release gate 失败。",
                requiredTests = listOf(
                    "CapabilityMatrixDocumentationTest",
                    "AgentCoreDocumentationTest",
                    "ModelManifestDocumentationTest",
                ),
                ownerAgent = CapabilityOwnerAgent.PerformanceQa,
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

package com.bytedance.zgx.pocketmind.docs

import com.bytedance.zgx.pocketmind.capability.CapabilityMatrix
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMatrixDocumentationTest {
    @Test
    fun capabilityMatrixJsonMatchesProductDescriptors() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documented = json.getJSONArray("productCapabilities")
        val documentedIds = (0 until documented.length()).map { index ->
            documented.getJSONObject(index).getString("capabilityId")
        }

        assertEquals(CapabilityMatrix.productPositioning, json.getString("productPositioning"))
        assertEquals(CapabilityMatrix.targetUserJob, json.getString("targetUserJob"))
        assertTrue(json.getString("productPositioning").contains("隐私优先"))
        assertTrue(json.getString("productPositioning").contains("必须确认执行"))
        assertTrue(json.getString("targetUserJob").contains("本地上下文留在本机"))

        assertEquals(
            CapabilityMatrix.productDescriptors.map { descriptor -> descriptor.capabilityId },
            documentedIds,
        )
        CapabilityMatrix.productDescriptors.forEachIndexed { index, descriptor ->
            val item = documented.getJSONObject(index)
            assertEquals(descriptor.capabilityId, item.getString("capabilityId"))
            assertEquals(descriptor.entrypoint, item.getString("entrypoint"))
            assertEquals(descriptor.toolName, item.nullableString("toolName"))
            assertEquals(descriptor.modelCapability?.name, item.nullableString("modelCapability"))
            assertEquals(descriptor.privacyLevel.name, item.getString("privacyLevel"))
            assertEquals(descriptor.requiresLocalModel, item.getBoolean("requiresLocalModel"))
            assertEquals(descriptor.remoteEligible, item.getBoolean("remoteEligible"))
            assertEquals(descriptor.confirmationPolicy.name, item.getString("confirmationPolicy"))
            assertEquals(descriptor.failureBehavior, item.getString("failureBehavior"))
            assertEquals(descriptor.requiredTests, item.getStringList("requiredTests"))
            assertEquals(descriptor.ownerAgent.name, item.getString("ownerAgent"))
        }
        assertEquals(
            listOf(
                "local_offline_chat",
                "explicit_memory",
                "shared_file_text_input",
                "remote_vision_image_input",
                "voice_transcript_input",
                "confirmed_device_tools",
                "auditable_agent_trace",
                "model_management",
                "run_data_receipt",
                "release_gate",
            ),
            documentedIds,
        )
    }

    @Test
    fun derivedToolDescriptorsHaveOwnersTestsAndStableToolCoverage() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documented = json.getJSONArray("toolCapabilities")
        val registry = ToolRegistry()
        val descriptors = CapabilityMatrix.toolDescriptors(registry)
        val documentedToolNames = (0 until documented.length()).map { index ->
            documented.getJSONObject(index).getString("toolName")
        }

        assertEquals(registry.specs().map { it.name }.toSet(), descriptors.mapNotNull { it.toolName }.toSet())
        assertEquals(registry.specs().map { it.name }, documentedToolNames)
        descriptors.forEach { descriptor ->
            assertTrue(descriptor.capabilityId.startsWith("tool_"))
            assertFalse(descriptor.requiredTests.isEmpty())
            assertTrue(descriptor.failureBehavior.isNotBlank())
        }
        descriptors.forEachIndexed { index, descriptor ->
            val item = documented.getJSONObject(index)
            assertDescriptorMatchesJson(descriptor, item)
        }
    }

    @Test
    fun capabilityRequiredTestsReferenceExistingTestClasses() {
        val testClasses = buildTestClassIndex(repoRoot())
        val missingClasses = CapabilityMatrix.allDescriptors()
            .flatMap { descriptor -> descriptor.requiredTests }
            .distinct()
            .filterNot { className -> className in testClasses }

        assertTrue(
            "Capability required test classes must exist: $missingClasses",
            missingClasses.isEmpty(),
        )
    }

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun assertDescriptorMatchesJson(
        descriptor: com.bytedance.zgx.pocketmind.capability.CapabilityDescriptor,
        item: JSONObject,
    ) {
        assertEquals(descriptor.capabilityId, item.getString("capabilityId"))
        assertEquals(descriptor.entrypoint, item.getString("entrypoint"))
        assertEquals(descriptor.toolName, item.nullableString("toolName"))
        assertEquals(descriptor.modelCapability?.name, item.nullableString("modelCapability"))
        assertEquals(descriptor.privacyLevel.name, item.getString("privacyLevel"))
        assertEquals(descriptor.requiresLocalModel, item.getBoolean("requiresLocalModel"))
        assertEquals(descriptor.remoteEligible, item.getBoolean("remoteEligible"))
        assertEquals(descriptor.confirmationPolicy.name, item.getString("confirmationPolicy"))
        assertEquals(descriptor.failureBehavior, item.getString("failureBehavior"))
        assertEquals(descriptor.requiredTests, item.getStringList("requiredTests"))
        assertEquals(descriptor.ownerAgent.name, item.getString("ownerAgent"))
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.getStringList(key: String): List<String> {
        val values = getJSONArray(key)
        return (0 until values.length()).map { index -> values.getString(index) }
    }

    private fun buildTestClassIndex(repoRoot: File): Set<String> =
        listOf(
            File(repoRoot, "app/src/test/java"),
            File(repoRoot, "app/src/androidTest/java"),
        )
            .flatMap { root -> root.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .map { file -> file.nameWithoutExtension }
            .toSet()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/capability_matrix.json").isFile }
            .absoluteFile
}

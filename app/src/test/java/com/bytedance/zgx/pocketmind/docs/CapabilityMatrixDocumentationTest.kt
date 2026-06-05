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

        assertEquals(
            CapabilityMatrix.productDescriptors.map { descriptor -> descriptor.capabilityId },
            documentedIds,
        )
        assertTrue(documentedIds.contains("local_offline_chat"))
        assertTrue(documentedIds.contains("explicit_memory"))
        assertTrue(documentedIds.contains("remote_vision_image_input"))
    }

    @Test
    fun derivedToolDescriptorsHaveOwnersTestsAndStableToolCoverage() {
        val registry = ToolRegistry()
        val descriptors = CapabilityMatrix.toolDescriptors(registry)

        assertEquals(registry.specs().map { it.name }.toSet(), descriptors.mapNotNull { it.toolName }.toSet())
        descriptors.forEach { descriptor ->
            assertTrue(descriptor.capabilityId.startsWith("tool_"))
            assertFalse(descriptor.requiredTests.isEmpty())
            assertTrue(descriptor.failureBehavior.isNotBlank())
        }
    }

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/capability_matrix.json").isFile }
            .absoluteFile
}

package com.bytedance.zgx.pocketmind.orchestration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDataReceiptTraceTest {
    @Test
    fun traceStoresReceiptPolicyAndCountsWithoutRawContent() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = store.createRun("raw prompt should not appear", sessionId = "session")
        store.appendStep(
            run.id,
            AgentStep.RunDataReceiptRecorded(
                RunDataReceipt(
                    destination = RunDataDestination.Remote,
                    currentPromptPrivacy = "RemoteEligible",
                    remoteHistoryCount = 2,
                    localOnlyHistoryFilteredCount = 1,
                    memoryHitCount = 0,
                    memoryContextIncluded = false,
                    deviceContextIncluded = false,
                    imageAttachmentCount = 1,
                    protectedSourceCount = 1,
                    rawContentPersisted = false,
                ),
            ),
        )

        val step = store.stepSummaries(run.id).single()
        val json = JSONObject(step.json)

        assertEquals("RunDataReceiptRecorded", step.type)
        assertTrue(step.summary.contains("remoteHistory=2"))
        assertEquals("Remote", json.getString("destination"))
        assertEquals(1, json.getInt("imageAttachmentCount"))
        assertFalse(json.getBoolean("memoryContextIncluded"))
        assertFalse(json.toString().contains("raw prompt"))
    }
}

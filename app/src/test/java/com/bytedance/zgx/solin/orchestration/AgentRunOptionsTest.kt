package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunOptionsTest {

    @Test
    fun defaultRuleFirstRunDoesNotExpectToolLoop() {
        assertFalse(AgentRunOptions().expectsToolLoop())
    }

    @Test
    fun modelFirstRemoteToolsExpectsToolLoop() {
        val options = AgentRunOptions(
            initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
        )

        assertTrue(options.expectsToolLoop())
    }

    @Test
    fun modelPlanningToolScopeExpectsToolLoop() {
        val options = AgentRunOptions(remoteToolScope = RemoteToolScope.ModelPlanning)

        assertTrue(options.expectsToolLoop())
    }

    @Test
    fun remoteGuiDrivingExpectsToolLoop() {
        val options = AgentRunOptions(remoteGuiDrivingEnabled = true)

        assertTrue(options.expectsToolLoop())
    }

    @Test
    fun reducedConfirmationsAloneDoesNotExpectToolLoop() {
        val options = AgentRunOptions(reduceDeviceActionConfirmations = true)

        assertFalse(options.expectsToolLoop())
    }
}

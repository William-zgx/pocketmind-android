package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.tool.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B1 trust-boundary guard: [SkillManifest.whenToUse] is model-facing display metadata and must NOT
 * participate in [authorizationContractHash]. Changing it must never invalidate an existing skill's
 * authorization (which would silently disable the skill), and it can never widen tools/risk.
 */
class SkillManifestWhenToUseTest {
    private fun manifest(whenToUse: String = ""): SkillManifest = SkillManifest(
        id = "test.skill",
        version = 1,
        title = "测试技能",
        description = "描述",
        triggerExamples = listOf("触发"),
        requiredTools = listOf("web_search"),
        inputSchemaJson = """{"type":"object","properties":{"input":{"type":"string"}}}""",
        riskLevel = RiskLevel.LowReadOnly,
        whenToUse = whenToUse,
    )

    @Test
    fun authorizationContractHashIgnoresWhenToUse() {
        val withoutHint = manifest(whenToUse = "").authorizationContractHash()
        val withHint = manifest(whenToUse = "当用户想搜索外部信息时使用").authorizationContractHash()
        val withOtherHint = manifest(whenToUse = "完全不同的说明文本").authorizationContractHash()

        assertEquals("whenToUse must not change the authorization contract", withoutHint, withHint)
        assertEquals("whenToUse must not change the authorization contract", withoutHint, withOtherHint)
    }

    @Test
    fun authorizationContractHashStillReactsToAuthorizingFields() {
        // Sanity check that the hash is not simply constant: an authorizing field (risk) changes it.
        val base = manifest().authorizationContractHash()
        val higherRisk = manifest().copy(riskLevel = RiskLevel.HighExternalSend).authorizationContractHash()
        assertNotEquals("risk level must remain part of the authorization contract", base, higherRisk)
    }
}

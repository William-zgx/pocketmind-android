package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.tool.RiskLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1: the advisory `<available_skills>` catalog must expose only non-authorizing display fields
 * (id, title, whenToUse) and stay LocalOnly, so it can help the model route a request without ever
 * becoming an authorization or tool-widening surface.
 */
class AvailableSkillsContributorTest {
    private val run = AgentRun(
        id = "run-1",
        input = "帮我搜索",
        state = AgentRunState.Planning,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun manifest(
        id: String,
        title: String,
        whenToUse: String,
    ): SkillManifest = SkillManifest(
        id = id,
        version = 1,
        title = title,
        description = "描述-$id",
        triggerExamples = listOf("触发-$id"),
        requiredTools = listOf("web_search"),
        inputSchemaJson = """{"type":"object","properties":{"secretField":{"type":"string"}}}""",
        riskLevel = RiskLevel.LowReadOnly,
        whenToUse = whenToUse,
    )

    @Test
    fun rendersIdTitleAndWhenToUseOnly() = runTest {
        val contributor = AvailableSkillsContributor(
            manifestsProvider = {
                listOf(manifest("info.lookup", "信息查找", "当用户需要外部信息时使用"))
            },
        )

        val card = contributor.contribute("帮我搜索", run)

        requireNotNull(card)
        assertEquals(MessagePrivacy.LocalOnly, card.privacy)
        assertTrue("must list the skill id", card.text.contains("info.lookup"))
        assertTrue("must list the title", card.text.contains("信息查找"))
        assertTrue("must list the whenToUse hint", card.text.contains("当用户需要外部信息时使用"))
        // Must NOT leak authorizing/config detail.
        assertFalse("must not expose input schema", card.text.contains("secretField"))
        assertFalse("must not expose required tools", card.text.contains("web_search"))
        assertTrue("must mark itself advisory", card.text.contains("仅供参考"))
    }

    @Test
    fun fallsBackToTitleWhenWhenToUseBlank() = runTest {
        val contributor = AvailableSkillsContributor(
            manifestsProvider = { listOf(manifest("s.one", "标题一", whenToUse = "")) },
        )

        val card = requireNotNull(contributor.contribute("x", run))
        assertTrue("blank whenToUse falls back to title", card.text.contains("标题一 — 标题一"))
    }

    @Test
    fun returnsNullWhenNoSkills() = runTest {
        val contributor = AvailableSkillsContributor(manifestsProvider = { emptyList() })
        assertNull(contributor.contribute("x", run))
    }
}

package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Replays the real-app UIAutomator dump corpus through the RUNTIME click-side entry point.
 *
 * Why this file exists: [UiAutomatorDumpReplayTest] replays the same fixtures through
 * [UiTargetResolver.explain], but until the two scorers were merged that only covered the offline
 * resolver — `SolinAccessibilityService`'s private copy of the arithmetic is what actually decides where
 * a real device gets tapped, and it had no regression defence at all. Both now go through
 * [scoreTargetCandidate]; this test pins the runtime side of that seam, so a weight or threshold change
 * that would mis-tap on a phone fails here rather than on a phone.
 *
 * The runtime side is exercised through [runtimeTargetMatchScore] + [runtimeNodeSearchLabel] rather than
 * through `NodeCandidate` because the latter needs a live `AccessibilityNodeInfo`. Those two functions
 * are everything the click path contributes beyond the shared core, except for the transient node-id
 * direct hit and the clickable-ancestor reach, which are covered separately below.
 */
class UiTargetScoringCoreParityTest {

    // ── Runtime click path on the real-app corpus ────────────────────────────────────────────────

    @Test
    fun runtimePathResolvesTheSameSearchEntryAsTheOfflineResolverOnEveryHomeDump() {
        // The four profiled shopping/maps apps plus four browsers. Each pair is (dump, model target).
        listOf(
            "taobao_search_home.xml" to "搜索入口",
            "pdd_search_home.xml" to "搜索入口",
            "jd_search_home.xml" to "搜索入口",
            "gaode_destination_home.xml" to "搜索入口",
            "quark_address_home.xml" to "地址栏",
            "chrome_address_home.xml" to "地址栏",
            "android_browser_address_home.xml" to "地址栏",
            "uc_address_home.xml" to "地址栏",
        ).forEach { (dump, target) ->
            val snapshot = loadDump(dump)
            val offline = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = target)

            val runtimeWinner = runtimeTopCandidate(snapshot, target)

            assertNotNull("$dump: offline resolver must select a search entry", offline.selectedNodeId)
            assertEquals(
                "$dump: runtime click path must tap the node the offline corpus pins",
                offline.selectedNodeId,
                runtimeWinner?.nodeId,
            )
        }
    }

    @Test
    fun runtimePathResolvesTheSameEditableFieldAsTheOfflineResolverOnEveryInputDump() {
        listOf(
            Triple("taobao_search_input.xml", "搜索输入框", "com.taobao.taobao:id/search_edit_text"),
            Triple("pdd_search_input.xml", "搜索商品", "com.xunmeng.pinduoduo:id/search_edit_text"),
            Triple("jd_search_input.xml", "搜索京东商品", "com.jingdong.app.mall:id/search_edit_text"),
            Triple("gaode_destination_input.xml", "目的地", "com.autonavi.minimap:id/search_edit_text"),
            Triple("quark_search_input.xml", "地址栏", "com.quark.browser:id/address_edit_text"),
            Triple("chrome_search_input.xml", "地址栏", "com.android.chrome:id/url_bar"),
            Triple("android_browser_search_input.xml", "地址栏", "com.android.browser:id/url"),
            Triple("uc_search_input.xml", "地址栏", "com.UCMobile:id/search_edit_text"),
        ).forEach { (dump, target, expectedNodeId) ->
            val snapshot = loadDump(dump)

            val runtimeWinner = runtimeTopCandidate(snapshot, target)

            assertEquals(
                "$dump: runtime click path must type into the real edit field",
                expectedNodeId,
                runtimeWinner?.nodeId,
            )
        }
    }

    @Test
    fun runtimePathResolvesTheSameSubmitControlAsTheOfflineResolverOnEveryInputDump() {
        listOf(
            "taobao_search_input.xml" to "com.taobao.taobao:id/search_submit",
            "pdd_search_input.xml" to "com.xunmeng.pinduoduo:id/search_submit",
            "jd_search_input.xml" to "com.jingdong.app.mall:id/search_submit",
            "gaode_destination_input.xml" to "com.autonavi.minimap:id/search_submit",
            "quark_search_input.xml" to "com.quark.browser:id/search_submit",
            "chrome_search_input.xml" to "com.android.chrome:id/search_submit",
            "android_browser_search_input.xml" to "com.android.browser:id/go",
            "uc_search_input.xml" to "com.UCMobile:id/search_submit",
        ).forEach { (dump, expectedNodeId) ->
            val snapshot = loadDump(dump)
            val offline = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

            val runtimeWinner = runtimeTopCandidate(snapshot, "提交搜索")

            assertEquals("$dump: offline expectation drifted", expectedNodeId, offline.selectedNodeId)
            assertEquals(
                "$dump: runtime click path must submit through the real submit control",
                expectedNodeId,
                runtimeWinner?.nodeId,
            )
        }
    }

    @Test
    fun runtimePathNeverTapsAVisualSearchOrScanControlAsASearchEntry() {
        // The single most damaging mis-tap in this domain: 拍照搜索 / 扫一扫 / 语音搜索 sit right next to
        // the search bar and open a camera or scanner instead of a keyboard.
        listOf(
            "taobao_search_home.xml" to
                listOf("com.taobao.taobao:id/camera_search", "com.taobao.taobao:id/same_style"),
            "pdd_search_home.xml" to listOf("com.xunmeng.pinduoduo:id/scan_entry"),
            "jd_search_home.xml" to listOf("com.jingdong.app.mall:id/scan_entry"),
            "quark_address_home.xml" to listOf("com.quark.browser:id/scan_entry"),
            "chrome_address_home.xml" to listOf("com.android.chrome:id/voice_search_button"),
            "android_browser_address_home.xml" to listOf("com.android.browser:id/voice_search"),
            "uc_address_home.xml" to listOf("com.UCMobile:id/scan_entry"),
        ).forEach { (dump, forbiddenNodeIds) ->
            val snapshot = loadDump(dump)
            val target = if (dump.contains("address")) "地址栏" else "搜索入口"

            val ranked = runtimeRankedCandidates(snapshot, target).map { it.nodeId }

            forbiddenNodeIds.forEach { forbidden ->
                assertFalse(
                    "$dump: $forbidden must not be a runtime search-entry candidate",
                    forbidden in ranked,
                )
            }
        }
    }

    @Test
    fun runtimePathNeverTapsAScrollableResultFeedAsASearchEntry() {
        // Feeds carry every search keyword on the screen, so a text-only match ranks them first; the
        // oversized-container + scrollable + commerce penalties are what keep them out.
        listOf(
            "taobao_search_home.xml" to "com.taobao.taobao:id/result_feed",
            "pdd_search_home.xml" to "com.xunmeng.pinduoduo:id/home_feed",
            "jd_search_home.xml" to "com.jingdong.app.mall:id/home_feed",
            "gaode_destination_home.xml" to "com.autonavi.minimap:id/map_canvas",
            "quark_address_home.xml" to "com.quark.browser:id/web_feed",
            "chrome_address_home.xml" to "com.android.chrome:id/feed_stream",
        ).forEach { (dump, feedNodeId) ->
            val snapshot = loadDump(dump)
            val target = if (dump.contains("address")) "地址栏" else "搜索入口"

            val winner = runtimeTopCandidate(snapshot, target)

            assertTrue(
                "$dump: runtime must not pick the feed/canvas $feedNodeId",
                winner?.nodeId != feedNodeId,
            )
        }
    }

    @Test
    fun runtimePathRefusesADisabledKeyboardSubmitAction() {
        // Fail-closed: a disabled control must not be reachable, no matter how well its label matches.
        val snapshot = loadDump("jd_disabled_keyboard_submit.xml")

        val ranked = runtimeRankedCandidates(snapshot, "提交搜索")

        assertTrue(
            "no submit target may be selected on a disabled-keyboard screen, got ${ranked.map { it.nodeId }}",
            ranked.isEmpty(),
        )
    }

    // ── The two runtime-only capabilities the merge had to preserve ──────────────────────────────

    @Test
    fun transientNodeIdDirectHitStillOutranksEveryTextMatchAndIgnoresTheKindFloor() {
        // The node-id path is the only way a model can reach an icon-only control with no text at all,
        // and it must not be gated by the kind minimum score. Assert both: the id hit beats the
        // best-possible text score, and it survives on a node that would score 0 semantically.
        val iconOnly = ScreenNode(
            id = "n4_deadbeef",
            text = "",
            contentDescription = "",
            className = "android.widget.ImageView",
            bounds = ScreenBounds(920, 86, 1012, 172),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
        )

        // No semantic evidence: the shared core alone rejects it outright.
        assertNull(
            runtimeTargetMatchScore(
                node = iconOnly,
                label = runtimeNodeSearchLabel("", "", "", "android.widget.ImageView"),
                target = "搜索入口",
                profile = null,
                rootBounds = ScreenBounds(0, 0, 1080, 2400),
            ),
        )
        // Addressed by id it is reachable, and outranks the strongest text evidence in the table.
        val idHit = requireNotNull(transientNodeIdTargetMatchScore(iconOnly.id, "n4_deadbeef_f00dbeef"))
        assertEquals(950, idHit)
        assertTrue(
            "an id direct hit plus actionability must beat any text-derived evidence base (max 900)",
            idHit + screenNodeActionabilityScore(iconOnly) > 900,
        )
    }

    @Test
    fun appProfileHintsStillLiftAProfiledSearchEntryOnTheRuntimePath() {
        // The package profile (AppInteractionProfiles) is a runtime-side input the merge had to keep
        // threading through. Same node, same target, profile present vs absent.
        val snapshot = loadDump("jd_search_home.xml")
        val searchBox = snapshot.nodes.single { it.id == "com.jingdong.app.mall:id/search_box" }
        val label = runtimeLabelFor(searchBox)
        val rootBounds = requireNotNull(runtimeRootBounds(snapshot))

        val withProfile = runtimeTargetMatchScore(
            node = searchBox,
            label = label,
            target = "搜索入口",
            profile = AppInteractionProfiles.forPackage("com.jingdong.app.mall"),
            rootBounds = rootBounds,
        )
        val withoutProfile = runtimeTargetMatchScore(
            node = searchBox,
            label = label,
            target = "搜索入口",
            profile = null,
            rootBounds = rootBounds,
        )

        assertNotNull(withProfile)
        assertNotNull(withoutProfile)
        assertTrue(
            "profile hints must still contribute: $withProfile should exceed $withoutProfile",
            withProfile!! > withoutProfile!!,
        )
    }

    @Test
    fun aRowClickableOnlyThroughItsParentIsStillASubmitCandidate() {
        // `effectivelyClickable` carries the runtime's clickableSelfOrAncestor() reach into the shared
        // core. A submit label whose own node reports clickable=false is common (a TextView inside a
        // clickable FrameLayout) and must remain tappable.
        val submitLabelInsideClickableParent = ScreenNode(
            id = "submit_text",
            text = "搜索",
            contentDescription = "",
            className = "android.widget.TextView",
            bounds = ScreenBounds(860, 86, 1032, 172),
            clickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
        )
        val rootBounds = ScreenBounds(0, 0, 1080, 2400)

        val withAncestorReach = runtimeTargetMatchScore(
            node = submitLabelInsideClickableParent,
            label = runtimeLabelFor(submitLabelInsideClickableParent),
            target = "提交搜索",
            profile = null,
            rootBounds = rootBounds,
            effectivelyClickable = { true },
        )
        val withoutAncestorReach = runtimeTargetMatchScore(
            node = submitLabelInsideClickableParent,
            label = runtimeLabelFor(submitLabelInsideClickableParent),
            target = "提交搜索",
            profile = null,
            rootBounds = rootBounds,
            effectivelyClickable = { false },
        )

        assertNotNull("a parent-clickable submit label must stay reachable", withAncestorReach)
        assertNull("without any clickable ancestor it must not be a submit target", withoutAncestorReach)
    }

    @Test
    fun freeTextTargetsWithNoSemanticKindStillResolveByDirectFieldMatch() {
        // `ui_tap(target="立即购买")` has no UiTargetKind; the resolver has no equivalent mode at all, so
        // the runtime's direct text/description table had to be preserved. Exact field match must
        // dominate an incidental mention inside a long row.
        val exact = ScreenNode(
            id = "exact",
            text = "开始导航",
            contentDescription = "",
            className = "android.widget.Button",
            bounds = ScreenBounds(40, 2000, 400, 2080),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
        )
        val mentionedInALongRow = exact.copy(
            id = "row",
            text = "推荐路线 30 分钟 开始导航 或选择其它路线查看详情",
        )
        val rootBounds = ScreenBounds(0, 0, 1080, 2400)

        val exactScore = runtimeTargetMatchScore(
            node = exact,
            label = runtimeLabelFor(exact),
            target = "开始导航",
            profile = null,
            rootBounds = rootBounds,
        )
        val rowScore = runtimeTargetMatchScore(
            node = mentionedInALongRow,
            label = runtimeLabelFor(mentionedInALongRow),
            target = "开始导航",
            profile = null,
            rootBounds = rootBounds,
        )

        assertNotNull(exactScore)
        assertNotNull(rowScore)
        assertTrue(
            "an exact field match ($exactScore) must outrank a long row that merely mentions it ($rowScore)",
            exactScore!! > rowScore!!,
        )
    }

    @Test
    fun disabledNodesAreRejectedBeforeAnyScoringOnTheRuntimePath() {
        val disabled = ScreenNode(
            id = "disabled_submit",
            text = "搜索",
            contentDescription = "",
            className = "android.widget.Button",
            bounds = ScreenBounds(860, 86, 1032, 172),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = false,
        )

        assertNull(
            runtimeTargetMatchScore(
                node = disabled,
                label = runtimeLabelFor(disabled),
                target = "提交搜索",
                profile = null,
                rootBounds = ScreenBounds(0, 0, 1080, 2400),
            ),
        )
    }

    // ── The two P0 regressions the scorer merge introduced ───────────────────────────────────────
    //
    // Both are invisible to the 25 UIAutomator fixtures: those are flat 4-7 node trees whose input
    // controls are all real EditTexts with clean single-purpose labels, so semantic scoring always
    // succeeds (never exercising the fallback) and no fixture has two competing editable fields.

    @Test
    fun aNonEditableInputBarStaysReachableWhenSemanticScoringFindsNothing() {
        // P0-2. `ui_tap(target="输入")` resolves kind=EditableField, so EVERY semantic term is 0 on a
        // comment bar rendered as a clickable TextView (not editable => semantic 0, no profile => hint 0).
        // Upstream fell through to the direct text/description table whenever semantic scoring came up
        // empty, whatever the kind; gating that fallback on `kind == null` made this node — whose own text
        // literally contains the target — unreachable, i.e. the comment box could not be tapped at all.
        val commentBar = ScreenNode(
            id = "com.example.app:id/comment_bar",
            text = "点击输入评论",
            contentDescription = "",
            className = "android.widget.TextView",
            bounds = ScreenBounds(24, 2100, 900, 2200),
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = true,
        )

        val score = runtimeTargetMatchScore(
            node = commentBar,
            label = runtimeLabelFor(commentBar),
            target = "输入",
            profile = null,
            rootBounds = ScreenBounds(0, 0, 1080, 2400),
        )

        assertEquals(UiTargetKind.EditableField, UiTargetResolver.kindForTarget("输入"))
        assertNotNull(
            "a kind!=null target whose semantic terms all score 0 must still reach the direct-text table",
            score,
        )
        // 650 (target inside `text`) + 120 clickable - 40 position - 1 noise. Pinned rather than merely
        // ">= floor" so the direct table's field-level tier and continuous noise curve both stay put.
        assertEquals(729, score)
    }

    @Test
    fun aBrowserAddressBarStaysReachableForABareInputTargetOnEveryHomeDump() {
        // Same regression on real fixtures: every browser home dump renders its address bar as a
        // non-editable TextView, so `target="输入"` (kind=EditableField) has no semantic evidence at all
        // and the direct-text table is the only thing that can reach it.
        listOf(
            "chrome_address_home.xml" to "com.android.chrome:id/search_box_text",
            "quark_address_home.xml" to "com.quark.browser:id/address_bar",
            "uc_address_home.xml" to "com.UCMobile:id/search_address_bar",
            "android_browser_address_home.xml" to "com.android.browser:id/url",
        ).forEach { (dump, expectedNodeId) ->
            val snapshot = loadDump(dump)

            val winner = runtimeTopCandidate(snapshot, "输入")

            assertEquals(
                "$dump: a bare 输入 target must still tap the address bar",
                expectedNodeId,
                winner?.nodeId,
            )
            assertEquals("$dump: direct-text evidence score drifted", 1049, winner?.score)
        }
    }

    @Test
    fun aCameraLabelledEditableFieldIsStillDemotedBelowAPlainOne() {
        // P0-3. The camera/voice/scan demotion is an INDEPENDENT subtraction upstream, and it only applies
        // to SearchEntry/EditableField — the two kinds whose real candidates are normally `editable`.
        // Folding it into `targetRiskPenalty`, which returns early for editable nodes, silenced it exactly
        // where it matters: these two fields then tied at 3070 and BFS document order decided, so a
        // `type_text` could open the camera instead of the keyboard.
        val cameraField = ScreenNode(
            id = "camera_field",
            text = "",
            contentDescription = "搜索宝贝 拍照",
            className = "android.widget.EditText",
            bounds = ScreenBounds(48, 86, 900, 172),
            clickable = true,
            editable = true,
            scrollable = false,
            enabled = true,
        )
        val plainField = cameraField.copy(
            id = "plain_field",
            contentDescription = "搜索宝贝",
            bounds = ScreenBounds(48, 200, 900, 286),
        )
        val profile = AppInteractionProfiles.forPackage("com.taobao.taobao")
        val rootBounds = ScreenBounds(0, 0, 1080, 2400)

        val cameraScore = runtimeTargetMatchScore(
            node = cameraField,
            label = runtimeLabelFor(cameraField),
            target = "搜索入口",
            profile = profile,
            rootBounds = rootBounds,
        )
        val plainScore = runtimeTargetMatchScore(
            node = plainField,
            label = runtimeLabelFor(plainField),
            target = "搜索入口",
            profile = profile,
            rootBounds = rootBounds,
        )

        assertNotNull(cameraScore)
        assertNotNull(plainScore)
        // Both remain selectable (a camera-labelled search box is still a search box — this is a demotion,
        // not a veto), but the plain field must win outright rather than tie on document order.
        assertTrue(
            "the 拍照 field ($cameraScore) must rank strictly below the plain one ($plainScore)",
            plainScore!! > cameraScore!!,
        )
        assertEquals("the 520 demotion tier drifted", 520, plainScore - cameraScore)
    }

    @Test
    fun theCameraDemotionSurvivesTheEditableEarlyReturnItWasHiddenBehind() {
        // Pins the structural cause directly: same label, same kind, differing only in `editable`. The
        // penalty must be present in BOTH, because it is a separate term from the oversized-container
        // geometry that legitimately stops at an editable node.
        val bounds = ScreenBounds(48, 86, 900, 172)
        val editableCamera = ScreenNode(
            id = "editable_camera",
            text = "",
            contentDescription = "搜索宝贝 拍照",
            className = "android.widget.EditText",
            bounds = bounds,
            clickable = true,
            editable = true,
            scrollable = false,
            enabled = true,
        )
        val editablePlain = editableCamera.copy(contentDescription = "搜索宝贝")
        val metrics = SnapshotBoundsMetrics.fromRootBounds(ScreenBounds(0, 0, 1080, 2400))

        fun score(node: ScreenNode): Int = requireNotNull(
            scoreTargetCandidate(
                node = node,
                label = node.contentDescription,
                kind = UiTargetKind.SearchEntry,
                normalizedTarget = "搜索入口".normalizedLookupKey(),
                profile = AppInteractionProfiles.forPackage("com.taobao.taobao"),
                metrics = metrics,
            ),
        ).score.riskPenalty

        assertEquals("an editable node must still carry the camera demotion", 520, score(editableCamera))
        assertEquals("a plain editable node carries no demotion", 0, score(editablePlain))
    }

    @Test
    fun aTargetlessResolveStillFindsNothingWithoutSemanticEvidence() {
        // Fail-closed guard on the widened fallback. `resolve(kind)` with no target normalizes to a blank
        // string, and `"".contains(anything)` plus `description == ""` would make every text-less node an
        // exact 900 "match" — handing a semantics-free winner to a caller that supplied no target at all.
        // The direct-text table must refuse a blank target outright.
        val snapshot = ScreenStateSnapshot(
            id = "no_semantic_evidence",
            packageName = null,
            capturedAtMillis = 1L,
            nodes = listOf(
                ScreenNode(
                    id = "top-action",
                    text = "首页",
                    contentDescription = "",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(12, 80, 200, 160),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                ),
            ),
            textSummary = "首页",
            truncated = false,
        )

        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry))
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.EditableField))
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch))
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.FilterEntry))
    }

    // ── The single minimum-score table ───────────────────────────────────────────────────────────

    @Test
    fun theMinimumScoreTableIsSharedByBothPathsAndTreatsFreeTextAsUnfloored() {
        // Was two verbatim copies (`minimumConfidence` / `minimumRuntimeScore`) that could drift.
        assertEquals(560, UiTargetKind.SearchEntry.minimumTargetScore())
        assertEquals(600, UiTargetKind.EditableField.minimumTargetScore())
        assertEquals(650, UiTargetKind.SubmitSearch.minimumTargetScore())
        assertEquals(430, UiTargetKind.FilterEntry.minimumTargetScore())
        assertEquals(650, UiTargetKind.ScrollContainer.minimumTargetScore())
        assertEquals(1, UiTargetKind.ResultItem.minimumTargetScore())
        assertEquals(1, (null as UiTargetKind?).minimumTargetScore())
    }

    // ── Helpers: replay a dump snapshot the way the live click path would see it ──────────────────

    private data class RuntimeCandidate(val nodeId: String, val score: Int)

    /**
     * Ranks [snapshot]'s nodes exactly as `findTargetCandidates` does on a device: the wide runtime
     * label, geometry relative to the window root, and the package profile.
     */
    private fun runtimeRankedCandidates(
        snapshot: ScreenStateSnapshot,
        target: String,
    ): List<RuntimeCandidate> {
        val profile = AppInteractionProfiles.forPackage(snapshot.packageName)
        val rootBounds = runtimeRootBounds(snapshot)
        return snapshot.nodes
            .mapNotNull { node ->
                runtimeTargetMatchScore(
                    node = node,
                    label = runtimeLabelFor(node),
                    target = target,
                    profile = profile,
                    rootBounds = rootBounds,
                )?.let { score -> RuntimeCandidate(nodeId = node.id, score = score) }
            }
            .sortedByDescending { candidate -> candidate.score }
    }

    private fun runtimeTopCandidate(snapshot: ScreenStateSnapshot, target: String): RuntimeCandidate? =
        runtimeRankedCandidates(snapshot, target).firstOrNull()

    /**
     * The runtime label: `AccessibilityNodeInfo.nodeSearchLabel()` also appends `viewIdResourceName`,
     * and in these dumps the resource id is the node id.
     */
    private fun runtimeLabelFor(node: ScreenNode): String =
        runtimeNodeSearchLabel(
            text = node.text,
            contentDescription = node.contentDescription,
            viewIdResourceName = node.id.takeIf { it.contains(":id/") },
            className = node.className,
        )

    /** The active window root's bounds — the outermost node in a UIAutomator dump. */
    private fun runtimeRootBounds(snapshot: ScreenStateSnapshot): ScreenBounds? =
        snapshot.nodes.firstNotNullOfOrNull { node -> node.bounds }

    private fun loadDump(fileName: String): ScreenStateSnapshot {
        val resourcePath = "ui_dumps/real_app_search/$fileName"
        val document = requireNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Missing test UIAutomator dump fixture: $resourcePath"
        }.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val nodes = mutableListOf<ScreenNode>()
        val root = document.documentElement
        collectNodes(root, path = "root", output = nodes)
        val packageName = firstPackageName(root)
        return ScreenStateSnapshot(
            id = fileName.substringBeforeLast('.'),
            packageName = packageName,
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.joinToString(" ") { node -> node.text.ifBlank { node.contentDescription } },
            truncated = false,
        )
    }

    private fun collectNodes(element: Element, path: String, output: MutableList<ScreenNode>) {
        if (element.tagName == "node") {
            val resourceId = element.attr("resource-id")
            output += ScreenNode(
                id = resourceId.ifBlank { path },
                text = element.attr("text"),
                contentDescription = element.attr("content-desc"),
                className = element.attr("class"),
                bounds = parseBounds(element.attr("bounds")),
                clickable = element.attr("clickable").toBoolean(),
                editable = element.attr("class").contains("EditText") || element.attr("editable").toBoolean(),
                scrollable = element.attr("scrollable").toBoolean(),
                enabled = element.attr("enabled").ifBlank { "true" }.toBoolean(),
            )
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) {
                collectNodes(child as Element, path = "$path/$index", output = output)
            }
        }
    }

    private fun firstPackageName(element: Element): String? {
        element.attr("package").takeIf { it.isNotBlank() }?.let { return it }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) {
                firstPackageName(child as Element)?.let { return it }
            }
        }
        return null
    }

    private fun parseBounds(raw: String): ScreenBounds? {
        val match = BoundsRegex.matchEntire(raw) ?: return null
        val (left, top, right, bottom) = match.destructured
        return ScreenBounds(
            left = left.toInt(),
            top = top.toInt(),
            right = right.toInt(),
            bottom = bottom.toInt(),
        )
    }

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private companion object {
        val BoundsRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}

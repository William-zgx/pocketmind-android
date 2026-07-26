package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: the ToolExecutor overlay-dismiss loop runs before a low-risk UI action. It is bounded, taps
 * only close/skip affordances, and is fail-closed against dangerous-action surfaces.
 */
class OverlayDismissLoopTest {
    @Test
    fun dismissesBlockingOverlayBeforeTapThenStopsWhenCleared() {
        // Observe order for a tap: (1) dangerousUiActionPreflight, (2) dismiss-loop round-1 observe,
        // (3) dismiss-loop re-observe after the close tap → cleared → stop; then the real tap runs.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(
                overlaySnapshot("overlay-preflight"), // (1) preflight: overlay, no dangerous control
                overlaySnapshot("overlay-round1"),    // (2) dismiss round 1: has close affordance
                clearSnapshot("clear-1"),             // (3) re-observe after tap → cleared, stop
            ),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-1",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        // Exactly one dismiss tap (on the close affordance) then the real tap on 商品.
        assertEquals(listOf("关闭", "商品"), provider.tapTargets)
    }

    @Test
    fun boundedToMaxRoundsOnStickyOverlay() {
        // Overlay never clears (sticky): the loop must stop after AD_DISMISS_MAX_ROUNDS and still
        // let the real action run — never an unbounded loop.
        val stickyObserves = List(SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS * 2 + 2) {
            overlaySnapshot("sticky-$it")
        }
        val provider = ScriptedControlProvider(observeSnapshots = stickyObserves)
        val executor = DeviceControlToolExecutor(provider = provider)

        executor.execute(
            ToolRequest(
                id = "tap-2",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        val dismissTaps = provider.tapTargets.count { it == "关闭" }
        assertTrue(
            "dismiss taps must be bounded by AD_DISMISS_MAX_ROUNDS (was $dismissTaps)",
            dismissTaps <= SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS,
        )
        assertTrue("the real tap must still run", provider.tapTargets.contains("商品"))
    }

    @Test
    fun neverAutoTapsWhenDangerousControlPresent() {
        // Overlay markers + a 立即购买 dangerous control: the dismiss loop must not tap the close
        // affordance (fail-closed), and the requested tap is blocked by the dangerous preflight.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(dangerousOverlaySnapshot("danger-1")),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-3",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        assertEquals("no dismiss tap and no real tap when a dangerous control is present", emptyList<String>(), provider.tapTargets)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
    }

    @Test
    fun failsClosedWhenDismissRevealsDangerousControl() {
        // Preflight #1 sees a benign overlay (dismissable). After the dismiss tap, the newly
        // revealed screen carries a 立即购买 dangerous control. The post-dismiss re-preflight must
        // catch it and block the real tap.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(
                overlaySnapshot("overlay-preflight"),   // (1) pre-dismiss preflight: benign overlay
                overlaySnapshot("overlay-round1"),       // (2) dismiss round 1: has close affordance
                dangerousRevealedSnapshot("revealed"),   // (3) re-observe after tap: dangerous control revealed
                dangerousRevealedSnapshot("revealed-2"), // (4) post-dismiss re-preflight: still dangerous
            ),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-4",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
        // The dismiss tap happened, but the real 商品 tap must NOT (blocked by re-preflight).
        assertEquals(listOf("关闭"), provider.tapTargets)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun node(
        id: String,
        text: String,
        clickable: Boolean = false,
    ): ScreenNode = ScreenNode(
        id = id,
        text = text,
        contentDescription = "",
        className = "android.view.View",
        bounds = ScreenBounds(0, 0, 100, 48),
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
    )

    private fun snapshot(id: String, nodes: List<ScreenNode>) = ScreenStateSnapshot(
        id = id,
        packageName = "com.example.app",
        capturedAtMillis = 1L,
        nodes = nodes,
        textSummary = nodes.joinToString(" ") { it.text },
        truncated = false,
    )

    private fun overlaySnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("promo-1", "限时抢购 专属权益"),
            node("promo-2", "已获得 红包 倒计时"),
            node("close", "关闭", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private fun dangerousOverlaySnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("promo-1", "限时抢购 专属权益"),
            node("promo-2", "已获得 红包"),
            node("buy", "立即购买", clickable = true),
            node("close", "关闭", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private fun clearSnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(node("content", "商品", clickable = true)),
    )

    // A screen with a dangerous control and NO overlay markers: dismiss won't loop, but the
    // dangerous-action preflight must block a tap here.
    private fun dangerousRevealedSnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("buy", "立即购买", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private class ScriptedControlProvider(
        private val observeSnapshots: List<ScreenStateSnapshot>,
    ) : CurrentScreenControlProvider {
        private var observeIndex = 0
        val tapTargets = mutableListOf<String>()

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            val snapshot = observeSnapshots.getOrElse(observeIndex) { observeSnapshots.last() }
            if (observeIndex < observeSnapshots.lastIndex) observeIndex += 1
            return ScreenStateReadResult.Available(snapshot)
        }

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult {
            tapTargets += target
            return succeeded()
        }

        override fun typeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            allowClipboardPasteFallback: Boolean,
        ): UiActionReadResult = succeeded()

        override fun submitSearch(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
            succeeded()

        override fun pressBack(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long): UiActionReadResult =
            succeeded()

        private fun succeeded(): UiActionReadResult = UiActionReadResult.Available(
            UiActionExecutionResult(
                status = UiActionStatus.Succeeded,
                before = null,
                after = null,
                summary = "ok",
                retryable = false,
            ),
        )
    }
}

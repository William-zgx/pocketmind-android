package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: bounded, accessibility-only blocking-overlay/ad dismissal.
 *
 * These guard tests cover the detection + fail-closed target selection that the ToolExecutor
 * dismiss loop relies on. The core trust-boundary property: the dismiss target is never surfaced on
 * a screen that also carries a dangerous-action control, so an auto-tap can never hit a
 * payment/authorize/delete surface.
 */
class BlockingOverlayGuardTest {
    private fun node(
        id: String,
        text: String = "",
        contentDescription: String = "",
        clickable: Boolean = false,
        bounds: ScreenBounds? = ScreenBounds(0, 0, 100, 48),
        enabled: Boolean = true,
    ): ScreenNode = ScreenNode(
        id = id,
        text = text,
        contentDescription = contentDescription,
        className = "android.view.View",
        bounds = bounds,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = enabled,
    )

    private fun snapshot(id: String, nodes: List<ScreenNode>): ScreenStateSnapshot =
        ScreenStateSnapshot(
            id = id,
            packageName = "com.example.app",
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.joinToString(" ") { it.text.ifBlank { it.contentDescription } },
            truncated = false,
        )

    @Test
    fun detectsBlockingOverlayWhenMarkersAndCloseAffordancePresent() {
        val overlay = snapshot(
            id = "overlay",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购 立即购买"),
                node(id = "promo-2", text = "已获得 红包 倒计时"),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertTrue(overlay.hasBlockingOverlay())
        val target = overlay.blockingOverlayDismissTarget()
        assertEquals("close", target?.id)
    }

    @Test
    fun doesNotDetectOverlayBelowMarkerThreshold() {
        val plain = snapshot(
            id = "plain",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购"),
                node(id = "content", text = "商品详情"),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertFalse("single marker must not trigger overlay detection", plain.hasBlockingOverlay())
        assertNull(plain.blockingOverlayDismissTarget())
    }

    @Test
    fun failsClosedWhenDangerousControlPresentOnOverlay() {
        // An overlay that also carries a 立即购买 dangerous control: the dismiss target must be
        // withheld so the auto-dismiss loop can never tap on a purchase surface.
        val dangerousOverlay = snapshot(
            id = "dangerous-overlay",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购 专属权益"),
                node(id = "promo-2", text = "已获得 红包"),
                node(id = "buy", text = "立即购买", clickable = true),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertTrue("overlay is still detected", dangerousOverlay.hasBlockingOverlay())
        assertNull(
            "dismiss target must be withheld when a dangerous control is present",
            dangerousOverlay.blockingOverlayDismissTarget(),
        )
    }

    @Test
    fun recognizesCommonDismissLabels() {
        listOf("关闭", "跳过", "我知道了", "close", "SKIP").forEach { label ->
            assertTrue("'$label' should be a dismiss label", label.isOverlayDismissLabel())
        }
        assertFalse("购买 must not be a dismiss label", "立即购买".isOverlayDismissLabel())
        assertFalse("overly long text is not a dismiss label", "关闭这个非常长的广告弹窗提示框内容".isOverlayDismissLabel())
    }
}

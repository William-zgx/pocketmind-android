package com.bytedance.zgx.solin.device

private val dangerousActionTextMarkers = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "提交订单",
    "购买",
    "立即购买",
    "删除",
    "发送",
    "发布",
    "授权",
    "允许",
    "同意",
)

private val strongDangerousActionTextMarkers = listOf(
    "确认支付",
    "立即支付",
    "确认付款",
    "立即付款",
    "确认转账",
    "确认删除",
    "删除此",
    "发送",
    "发布",
    "授权登录",
    "确认授权",
    "同意授权",
    "提交订单",
    "确认下单",
    "立即购买",
)

private val standaloneDangerousActionTextMarkers = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "购买",
    "删除",
    "发送",
    "发布",
    "授权",
    "允许",
    "同意",
)

internal fun String?.hasDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return dangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun String?.hasOcrDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    if (hasStrongDangerousActionText()) return true
    return standaloneDangerousActionTextMarkers.any { marker ->
        normalized == marker.normalizedLookupKey()
    }
}

internal fun String?.hasStrongDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return strongDangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun ScreenStateSnapshot.hasDangerousActionControl(): Boolean =
    nodes.any { node -> node.hasDangerousActionControl() }

private fun ScreenNode.hasDangerousActionControl(): Boolean {
    if (!enabled) return false
    if (!clickable && !editable && !scrollable) return false
    val label = text.ifBlank { contentDescription }
    return label.hasDangerousActionText()
}

// ── Blocking overlay / interstitial detection (shared by the search-focus loop and the
// ToolExecutor dismiss loop) ────────────────────────────────────────────────────────────────

private val blockingOverlayMarkers = listOf(
    "优惠券",
    "立即购买",
    "倒计时",
    "限时抢购",
    "专属权益",
    "已获得",
    "红包",
    "弹窗",
)

private val overlayDismissLabels = listOf(
    "关闭",
    "取消",
    "跳过",
    "稍后",
    "暂不",
    "我知道了",
    "不感兴趣",
)

internal fun String?.isOverlayDismissLabel(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank() || normalized.length > 8) return false
    // Require an (almost) exact close/skip affordance, not a substring of longer content text.
    // A real dialog close control is a short standalone label; matching substrings of long text
    // (e.g. a product description that happens to contain 关闭/跳过) caused false-positive taps.
    if (overlayDismissLabels.any { label -> normalized == label.normalizedLookupKey() }) return true
    val exactAscii = setOf("close", "dismiss", "skip", "x", "×")
    return normalized in exactAscii
}

private fun String?.hasBlockingOverlayMarker(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return blockingOverlayMarkers.any { marker -> normalized.contains(marker.normalizedLookupKey()) }
}

private fun ScreenNode.overlayLabel(): String = text.ifBlank { contentDescription }

/**
 * Heuristic: the screen carries a promotional/interstitial overlay that occludes real content.
 * True when at least two nodes match blocking-overlay markers (coupon/countdown/red-packet/…),
 * matching the search-focus loop's existing `looksLikeSearchBlockingOverlay` threshold.
 */
internal fun ScreenStateSnapshot.hasBlockingOverlay(): Boolean {
    val markerCount = nodes.count { node -> node.overlayLabel().hasBlockingOverlayMarker() }
    return markerCount >= 2
}

/**
 * The dismiss target for a blocking overlay, if one is present: an enabled, actionable node whose
 * label is a close/skip affordance. Returns null when there is no overlay or no safe close control.
 * Fail-closed: never returns a target on a screen that also carries a dangerous-action control —
 * the caller must additionally route the tap through the dangerous-action preflight.
 */
internal fun ScreenStateSnapshot.blockingOverlayDismissTarget(): ScreenNode? {
    if (!hasBlockingOverlay()) return null
    if (hasDangerousActionControl()) return null
    return nodes.firstOrNull { node ->
        node.enabled &&
            node.clickable &&
            node.overlayLabel().isOverlayDismissLabel()
    }
}

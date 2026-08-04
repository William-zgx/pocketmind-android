package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.multimodal.RawScreenshotProvider
import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult

/**
 * Captures the current screen for the opt-in remote-vision GUI automation path via
 * [SolinAccessibilityService.performTakeScreenshotRaw] (AccessibilityService.takeScreenshot, API 30+).
 *
 * Unlike the MediaProjection-backed OCR provider, this needs NO system screen-cast consent dialog and
 * NO foreground service: the already-connected, user-enabled accessibility service captures directly.
 * Screen-pixel egress is governed upstream by the in-app opt-in toggle + the replanner's first-confirm
 * gate + the per-send remote-send audit — not by any per-capture OS consent. [requestId] is a
 * correlation/log id only. Fails closed (Failed) when the accessibility service is not connected.
 */
class AccessibilityRawScreenshotProvider : RawScreenshotProvider {
    override fun captureCurrentScreenshotRaw(
        requestId: String,
        nowMillis: Long,
    ): RawScreenshotReadResult =
        SolinAccessibilityService.performTakeScreenshotRaw(requestId)
}

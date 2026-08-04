package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRawScreenshotProviderTest {
    /**
     * With no accessibility service connected (the JVM-unit-test default), the raw screenshot
     * capture must fail closed — never throw, never return pixels. This is the exact posture the
     * remote-vision replanner relies on to stop the loop when the service is unavailable.
     */
    @Test
    fun failsClosedWhenAccessibilityServiceNotConnected() {
        val result = AccessibilityRawScreenshotProvider().captureCurrentScreenshotRaw("obs-1")
        assertTrue(result is RawScreenshotReadResult.Failed)
    }

    @Test
    fun takeScreenshotErrorMapsToFailedWithCode() {
        val result = takeScreenshotErrorToResult(errorCode = 3)
        assertTrue(result is RawScreenshotReadResult.Failed)
        assertTrue(result.reason.contains("3"))
    }
}

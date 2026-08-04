package com.bytedance.zgx.solin.multimodal

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVisionScreenshotContractTest {
    private val valid = RemoteVisionScreenshotBoundary(
        requiresRemoteVisionOptIn = true,
        inAppScreenEgressConsentRequired = true,
        firstSendConfirmationRequired = true,
        crossesRemoteBoundary = true,
        persistsPixels = false,
        producesSemanticVisualUnderstanding = true,
        tapsExecutedLocally = true,
    )

    @Test
    fun validBoundaryHasNoErrors() {
        assertTrue(RemoteVisionScreenshotContract.validateBoundary(valid).isEmpty())
    }

    @Test
    fun boundaryRejectsMissingOptInConsentAndConfirmation() {
        val errors = RemoteVisionScreenshotContract.validateBoundary(
            valid.copy(
                requiresRemoteVisionOptIn = false,
                inAppScreenEgressConsentRequired = false,
                firstSendConfirmationRequired = false,
            ),
        )
        assertTrue(errors.any { it.contains("opt-in") })
        assertTrue(errors.any { it.contains("in-app opt-in and first-confirm") })
        assertTrue(errors.any { it.contains("first-send") })
    }

    @Test
    fun boundaryRejectsPersistedPixels() {
        val errors = RemoteVisionScreenshotContract.validateBoundary(valid.copy(persistsPixels = true))
        assertTrue(errors.any { it.contains("must not persist pixels") })
    }

    @Test
    fun boundaryRejectsWhenPixelsNeverCrossBoundary() {
        // A remote-vision capture that never sends pixels is a misconfiguration — the whole point
        // is that the remote model sees them. This is the deliberate inverse of the OCR contract.
        val errors = RemoteVisionScreenshotContract.validateBoundary(valid.copy(crossesRemoteBoundary = false))
        assertTrue(errors.any { it.contains("cross the remote boundary") })
    }

    @Test
    fun boundaryRejectsWhenNoVisualSemanticsOrRemoteTapExecution() {
        val errors = RemoteVisionScreenshotContract.validateBoundary(
            valid.copy(
                producesSemanticVisualUnderstanding = false,
                tapsExecutedLocally = false,
            ),
        )
        assertTrue(errors.any { it.contains("visual semantics") })
        assertTrue(errors.any { it.contains("executed locally") })
    }
}

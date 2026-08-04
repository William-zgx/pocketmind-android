package com.bytedance.zgx.solin.multimodal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks the OCR coordinate contract: bounds recognized on the downscaled OCR bitmap are always
 * mapped back to source-screenshot (real screen) pixels before leaving the provider, so downstream
 * tap dispatch can use them verbatim.
 */
class CurrentScreenshotOcrCoordinateTest {
    @Test
    fun unscalesAllNestingLevelsBackToSourcePixels() {
        // 2400x1080 portrait screen downscaled to longest-edge 1600 => 720x1600, scale 1.5 on both axes.
        val mapped = listOf(
            OcrTextBlock(
                text = "确认支付",
                bounds = OcrTextBounds(left = 100, top = 200, right = 300, bottom = 260),
                lines = listOf(
                    OcrTextLine(
                        text = "确认支付",
                        bounds = OcrTextBounds(left = 100, top = 200, right = 300, bottom = 260),
                        elements = listOf(
                            OcrTextElement(
                                text = "确认",
                                bounds = OcrTextBounds(left = 100, top = 200, right = 200, bottom = 260),
                            ),
                            OcrTextElement(
                                text = "支付",
                                bounds = OcrTextBounds(left = 200, top = 200, right = 300, bottom = 260),
                            ),
                        ),
                    ),
                ),
            ),
        ).mappedToSourcePixels(
            sourceWidth = 1080,
            sourceHeight = 2400,
            ocrWidth = 720,
            ocrHeight = 1600,
        )

        val block = mapped.single()
        assertEquals(OcrTextBounds(left = 150, top = 300, right = 450, bottom = 390), block.bounds)
        val line = block.lines.single()
        assertEquals(OcrTextBounds(left = 150, top = 300, right = 450, bottom = 390), line.bounds)
        assertEquals(
            listOf(
                OcrTextBounds(left = 150, top = 300, right = 300, bottom = 390),
                OcrTextBounds(left = 300, top = 300, right = 450, bottom = 390),
            ),
            line.elements.map { element -> element.bounds },
        )
        // Text payload must survive the coordinate remap untouched.
        assertEquals("确认支付", block.text)
        assertEquals(listOf("确认", "支付"), line.elements.map { element -> element.text })
    }

    @Test
    fun unscalesXAndYWithIndependentScaleFactors() {
        // Deliberately non-uniform: scaledForOcr rounds width and height separately, so the two
        // axes can carry different effective scale factors and must not share one factor.
        val mapped = listOf(
            OcrTextBlock(
                text = "重启",
                bounds = OcrTextBounds(left = 10, top = 10, right = 50, bottom = 30),
            ),
        ).mappedToSourcePixels(
            sourceWidth = 400,
            sourceHeight = 1200,
            ocrWidth = 100,
            ocrHeight = 200,
        )

        // scaleX = 400/100 = 4, scaleY = 1200/200 = 6.
        assertEquals(
            OcrTextBounds(left = 40, top = 60, right = 200, bottom = 180),
            mapped.single().bounds,
        )
    }

    @Test
    fun roundsHalfPixelsAndClampsToSourceBounds() {
        val mapped = listOf(
            OcrTextBlock(
                text = "边缘",
                bounds = OcrTextBounds(left = 1, top = 1, right = 720, bottom = 1600),
            ),
        ).mappedToSourcePixels(
            sourceWidth = 1080,
            sourceHeight = 2400,
            ocrWidth = 720,
            ocrHeight = 1600,
        )

        // 1 * 1.5 = 1.5 rounds to 2; the far edge maps exactly onto the source dimensions and is
        // never allowed past them.
        assertEquals(
            OcrTextBounds(left = 2, top = 2, right = 1080, bottom = 2400),
            mapped.single().bounds,
        )
    }

    @Test
    fun returnsReceiverUnchangedWhenNoScalingHappened() {
        val blocks = listOf(
            OcrTextBlock(
                text = "无缩放",
                bounds = OcrTextBounds(left = 5, top = 6, right = 7, bottom = 8),
            ),
        )

        assertSame(
            blocks,
            blocks.mappedToSourcePixels(
                sourceWidth = 1080,
                sourceHeight = 1600,
                ocrWidth = 1080,
                ocrHeight = 1600,
            ),
        )
    }

    @Test
    fun returnsReceiverUnchangedForEmptyOrNonPositiveDimensions() {
        val blocks = listOf(
            OcrTextBlock(
                text = "降级",
                bounds = OcrTextBounds(left = 5, top = 6, right = 7, bottom = 8),
            ),
        )

        assertSame(
            emptyList<OcrTextBlock>(),
            emptyList<OcrTextBlock>().mappedToSourcePixels(1080, 2400, 720, 1600),
        )
        assertSame(blocks, blocks.mappedToSourcePixels(1080, 2400, 0, 1600))
        assertSame(blocks, blocks.mappedToSourcePixels(1080, 2400, 720, 0))
        assertSame(blocks, blocks.mappedToSourcePixels(0, 2400, 720, 1600))
        assertSame(blocks, blocks.mappedToSourcePixels(1080, 0, 720, 1600))
    }

    @Test
    fun preservesMissingBoundsAtEveryLevel() {
        val mapped = listOf(
            OcrTextBlock(
                text = "无坐标",
                bounds = null,
                lines = listOf(
                    OcrTextLine(
                        text = "无坐标",
                        bounds = null,
                        elements = listOf(OcrTextElement(text = "无坐标", bounds = null)),
                    ),
                ),
            ),
        ).mappedToSourcePixels(
            sourceWidth = 1080,
            sourceHeight = 2400,
            ocrWidth = 720,
            ocrHeight = 1600,
        )

        val block = mapped.single()
        assertNull(block.bounds)
        assertNull(block.lines.single().bounds)
        assertNull(block.lines.single().elements.single().bounds)
    }
}

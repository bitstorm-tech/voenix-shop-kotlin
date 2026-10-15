package shop.voenix.article.spod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The measurement rule on pictures the test draws itself (SPOD sync decision 7): no partner, no
 * database — bytes in, a frame or a reason out. The boxes are the ones `CalibrationFixtures.kt`
 * draws for the sync tests, so the two suites agree on what a preview measures to.
 */
internal class PrintFrameMeasurementTest {
    @Test
    fun `an exact box measures to its edges in percent`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(285, 188, 425, 425)))

        assertEquals(PrintFrameMeasurement.Measured(TEE_FRAME), measured)
    }

    /** The renderer blends the design's edge into the garment; those pixels are not the design. */
    @Test
    fun `a translucent rim around the box is left out`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(285, 188, 425, 425), antiAliased = true))

        assertEquals(PrintFrameMeasurement.Measured(TEE_FRAME), measured)
    }

    @Test
    fun `a box with a hole is no flat print`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(356, 189, 284, 284), holed = true))

        assertEquals(PrintFrameMeasurement.Failed(MeasurementFailure.LOW_FILL), measured)
    }

    @Test
    fun `a picture without magenta has no rectangle`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(0, 0, 0, 0)))

        assertEquals(PrintFrameMeasurement.Failed(MeasurementFailure.NO_RECTANGLE), measured)
    }

    /** A few stray pixels of the colour are not a print area either. */
    @Test
    fun `a box smaller than two percent of the picture has no rectangle`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(500, 500, 19, 30)))

        assertEquals(PrintFrameMeasurement.Failed(MeasurementFailure.NO_RECTANGLE), measured)
    }

    @Test
    fun `a box touching the border ends exactly at one hundred percent`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(600, 700, 400, 300)))

        assertEquals(
            PrintFrameMeasurement.Measured(
                PrintFrame(leftPct = 60.0, topPct = 70.0, widthPct = 40.0, heightPct = 30.0)
            ),
            measured,
        )
    }

    /** The right edge is rounded on its own, so `left + width` is the rounded right edge. */
    @Test
    fun `the percentages are rounded half up to two decimals`() {
        val measured = measurePrintFrame(magentaBoxPng(Box(100, 100, 100, 100), edge = 300))

        assertEquals(
            PrintFrameMeasurement.Measured(
                PrintFrame(leftPct = 33.33, topPct = 33.33, widthPct = 33.34, heightPct = 33.34)
            ),
            measured,
        )
    }

    @Test
    fun `bytes that are no picture are unreadable`() {
        val measured = measurePrintFrame(byteArrayOf(1, 2, 3, 4))

        assertEquals(PrintFrameMeasurement.Failed(MeasurementFailure.UNREADABLE_IMAGE), measured)
    }

    @Test
    fun `the calibration design is an opaque magenta square`() {
        val measured = measurePrintFrame(calibrationDesignPng())

        val frame = assertIs<PrintFrameMeasurement.Measured>(measured).frame
        assertEquals(
            PrintFrame(leftPct = 0.0, topPct = 0.0, widthPct = 100.0, heightPct = 100.0),
            frame,
        )
    }
}

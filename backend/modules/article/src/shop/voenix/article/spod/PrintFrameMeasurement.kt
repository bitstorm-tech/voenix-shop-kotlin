package shop.voenix.article.spod

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import javax.imageio.ImageIO

/**
 * The calibration design and the rule that reads its print back off a preview (SPOD sync decision
 * 7).
 *
 * The partner's API says nothing about where a hotspot lies on a mockup, so the sync *looks*: it
 * uploads a square of one flat colour as a design, lets the partner render it on the hotspot, and
 * measures the rectangle of that colour in the rendered picture. Magenta is the colour because no
 * garment and no mockup background is magenta, so every pixel of it in the preview is the design
 * and nothing else.
 *
 * This file is pure: bytes in, a frame or a reason out, no partner call. The square is 2000 × 2000
 * so that it passes the partner's resolution check on every front hotspot (issue #260, open
 * question 1); its aspect ratio is `1:1` because that is the one ratio a SPOD article prints
 * (decision 10).
 */
internal const val CALIBRATION_DESIGN_FILE_NAME: String = "calibration-1x1.png"

/** The PNG bytes of the calibration design: an opaque magenta square. Generated, never shipped. */
internal fun calibrationDesignPng(): ByteArray {
    val image =
        BufferedImage(CALIBRATION_EDGE_PIXELS, CALIBRATION_EDGE_PIXELS, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.MAGENTA
        graphics.fillRect(0, 0, image.width, image.height)
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { stream ->
        ImageIO.write(image, "png", stream)
        stream.toByteArray()
    }
}

/** What measuring one preview answered: the frame, or the bounded reason there is none. */
internal sealed interface PrintFrameMeasurement {
    data class Measured(val frame: PrintFrame) : PrintFrameMeasurement

    data class Failed(val reason: MeasurementFailure) : PrintFrameMeasurement
}

/** Why a preview did not yield a frame; the names travel into the sync report's warning detail. */
internal enum class MeasurementFailure {
    /** No image reader could decode the bytes. */
    UNREADABLE_IMAGE,

    /** No magenta at all, or a box too small to be a print area rather than a stray pixel. */
    NO_RECTANGLE,

    /** The magenta box has holes or ragged edges: the design was not printed as one flat square. */
    LOW_FILL,
}

/**
 * Measures the magenta rectangle in [png] as a frame in percent of the picture.
 *
 * A pixel is magenta when it is opaque (alpha of at least [MIN_ALPHA], so the anti-aliased edge the
 * renderer draws around the design is left out) and within [CHANNEL_TOLERANCE] of `#FF00FF` on each
 * channel. The frame is the bounding box of those pixels, and it validates itself (issue #260,
 * decision 12): the box has to be at least [MIN_BOX_PERCENT] of the picture in both directions and
 * at least [MIN_FILL_PERCENT] of it has to be magenta, otherwise what was found is not a flat print
 * of the square and the article gets no frame rather than a wrong one.
 *
 * The percentages carry two decimals, rounded half up. `left + width` is at most 100 by
 * construction: the right edge is rounded first and the width is the difference of the two rounded
 * edges, so the frame never leaves the picture by a rounding hair — which the database would
 * refuse.
 */
@Suppress("ReturnCount")
internal fun measurePrintFrame(png: ByteArray): PrintFrameMeasurement {
    val image =
        decode(png) ?: return PrintFrameMeasurement.Failed(MeasurementFailure.UNREADABLE_IMAGE)
    val width = image.width
    val pixels = image.getRGB(0, 0, width, image.height, null, 0, width)
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = -1
    var maxY = -1
    var magenta = 0L
    pixels.forEachIndexed { index, argb ->
        if (isMagenta(argb)) {
            val x = index % width
            val y = index / width
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            magenta++
        }
    }
    if (magenta == 0L) return PrintFrameMeasurement.Failed(MeasurementFailure.NO_RECTANGLE)

    val boxWidth = maxX - minX + 1
    val boxHeight = maxY - minY + 1
    if (
        boxWidth * PERCENT < MIN_BOX_PERCENT * width ||
            boxHeight * PERCENT < MIN_BOX_PERCENT * image.height
    ) {
        return PrintFrameMeasurement.Failed(MeasurementFailure.NO_RECTANGLE)
    }
    if (magenta * PERCENT < MIN_FILL_PERCENT * boxWidth.toLong() * boxHeight) {
        return PrintFrameMeasurement.Failed(MeasurementFailure.LOW_FILL)
    }

    val left = percent(minX, width)
    val top = percent(minY, image.height)
    return PrintFrameMeasurement.Measured(
        PrintFrame(
            leftPct = left.toDouble(),
            topPct = top.toDouble(),
            widthPct = (percent(maxX + 1, width) - left).toDouble(),
            heightPct = (percent(maxY + 1, image.height) - top).toDouble(),
        )
    )
}

private fun isMagenta(argb: Int): Boolean {
    val alpha = argb ushr ALPHA_SHIFT
    val red = (argb shr RED_SHIFT) and CHANNEL_MASK
    val green = (argb shr GREEN_SHIFT) and CHANNEL_MASK
    val blue = argb and CHANNEL_MASK
    return alpha >= MIN_ALPHA &&
        CHANNEL_MAX - red <= CHANNEL_TOLERANCE &&
        green <= CHANNEL_TOLERANCE &&
        CHANNEL_MAX - blue <= CHANNEL_TOLERANCE
}

/** [pixel] as a percentage of [edge], with two decimals rounded half up. */
private fun percent(pixel: Int, edge: Int): BigDecimal =
    BigDecimal(pixel * PERCENT).divide(BigDecimal(edge), FRAME_SCALE, RoundingMode.HALF_UP)

/** Decodes through ImageIO like the production module does; bytes no reader claims are `null`. */
private fun decode(png: ByteArray): BufferedImage? =
    try {
        ImageIO.read(ByteArrayInputStream(png))
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private const val CALIBRATION_EDGE_PIXELS = 2000

/** Below this alpha a pixel belongs to the renderer's anti-aliased edge, not to the design. */
private const val MIN_ALPHA = 250

/** How far a channel may stray from `#FF00FF` and still be the design: the renderer resamples. */
private const val CHANNEL_TOLERANCE = 24

/** The box has to span at least this much of the picture in both directions. */
private const val MIN_BOX_PERCENT = 2

/** At least this much of the bounding box has to be magenta for it to count as a flat square. */
private const val MIN_FILL_PERCENT = 97

private const val PERCENT = 100
private const val FRAME_SCALE = 2
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val CHANNEL_MAX = 0xFF

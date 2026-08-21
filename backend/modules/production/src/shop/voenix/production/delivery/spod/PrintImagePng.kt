package shop.voenix.production.delivery.spod

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Turns one generated print image into the PNG the print-on-demand partner accepts.
 *
 * The shop stores every generated image as WebP; the partner's design upload takes PNG of at most
 * 10 MB. Nothing else in this backend can do that conversion — the image module is not a dependency
 * of production, and adding one for a format change would be a module dependency bought with a
 * decoder — so production does it itself, with the very ImageIO path the PDF renderer already uses:
 * the registered `webp-imageio` reader claims the RIFF container, and `ImageIO` writes PNG back
 * out.
 *
 * Two budgets shape the result, and both are the partner's rather than ours:
 *
 * - **A pixel cap.** The longest edge is scaled down to at most [MAX_EDGE_PIXELS]. A print image is
 *   "scaled as big as possible" onto the garment, so more pixels than that buy no visible quality
 *   and only cost upload time.
 * - **A byte budget.** PNG is lossless, so its size cannot be dialled in — it can only be made
 *   smaller by making the image smaller. When the encoded bytes exceed [MAX_BYTES] the image is
 *   halved and re-encoded, at most [MAX_SHRINK_ATTEMPTS] times. The budget sits below the partner's
 *   hard 10 MB, so a multipart envelope never pushes an accepted image over the line.
 *
 * The bounded shrink loop is the point: without it, one pathological image would either be retried
 * forever by the worker or drive the conversion into an unbounded loop. Three halvings reduce a
 * 4096 px edge to 512 px, and an image that still does not fit is a typed failure a human can act
 * on rather than a job that never finishes.
 */
internal object PrintImagePng {
    /** The partner scales a design onto the garment; beyond this many pixels nothing improves. */
    const val MAX_EDGE_PIXELS: Int = 4096

    /** 9.5 MiB — below the partner's 10 MB limit, with room for the multipart envelope. */
    const val MAX_BYTES: Int = 9_961_472

    /** Three halvings take a 4096 px edge to 512 px. Anything beyond that is not a print image. */
    const val MAX_SHRINK_ATTEMPTS: Int = 3

    /**
     * The PNG bytes of the image at [path], or the bounded reason why there are none.
     *
     * A `null` path is a line whose print image was never generated, which is exactly as retryable
     * as a file that is not there yet: both answer [PrintImageError.PRINT_IMAGE_MISSING] and both
     * heal once generation catches up.
     *
     * [maxBytes] is the budget and defaults to [MAX_BYTES]. It is a parameter only so a test can
     * pin the shrink loop with an image a laptop can hold: a real image that overruns 9.5 MB after
     * three halvings would have to be enormous, and building one in a test would prove the same
     * thing much more slowly.
     */
    fun convert(path: Path?, maxBytes: Int = MAX_BYTES): PrintImagePngResult {
        if (path == null || !Files.isRegularFile(path)) {
            return PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_MISSING)
        }
        val decoded = decode(path)
        return if (decoded == null) {
            failedUnreadable()
        } else {
            encodeWithinBudget(capped(decoded), maxBytes)
        }
    }

    /**
     * Encodes to PNG, halving the image and trying again while it is over budget.
     *
     * The loop counts *shrinks*, not encodes: the first encode is the image as it came out of the
     * pixel cap, and only a result over budget costs an attempt.
     */
    private fun encodeWithinBudget(image: BufferedImage, maxBytes: Int): PrintImagePngResult {
        var current = image
        var fitting: ByteArray? = null
        var shrinks = 0
        while (fitting == null && shrinks <= MAX_SHRINK_ATTEMPTS) {
            val bytes = encode(current) ?: return failedUnreadable()
            if (bytes.size <= maxBytes) fitting = bytes else current = halved(current)
            shrinks++
        }
        return fitting?.let(PrintImagePngResult::Converted)
            ?: PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_TOO_LARGE)
    }

    private fun failedUnreadable(): PrintImagePngResult =
        PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_UNREADABLE)

    /**
     * Decodes through ImageIO rather than through any format-specific API, exactly like the PDF
     * renderer: ImageIO consults every registered reader, which is how a WebP original is read at
     * all. A file no reader claims yields `null`; a reader that stumbles over the content reports
     * it as an I/O or an illegal-argument failure, and all three mean the same thing here.
     */
    private fun decode(path: Path): BufferedImage? =
        try {
            ImageIO.read(path.toFile())
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun encode(image: BufferedImage): ByteArray? =
        try {
            ByteArrayOutputStream().use { stream ->
                if (ImageIO.write(image, "png", stream)) stream.toByteArray() else null
            }
        } catch (_: IOException) {
            null
        }

    /** The image scaled so that its longest edge is at most [MAX_EDGE_PIXELS]; smaller is kept. */
    private fun capped(image: BufferedImage): BufferedImage {
        val longestEdge = maxOf(image.width, image.height)
        if (longestEdge <= MAX_EDGE_PIXELS) return image
        val factor = MAX_EDGE_PIXELS.toDouble() / longestEdge
        return scaled(image, (image.width * factor).toInt(), (image.height * factor).toInt())
    }

    private fun halved(image: BufferedImage): BufferedImage =
        scaled(image, image.width / 2, image.height / 2)

    /**
     * Redraws the image at the given size with bilinear interpolation.
     *
     * Transparency is preserved when the source has it: a chest print is meant to leave the shirt
     * visible around it, and flattening the alpha channel here would print a rectangle of white.
     * Neither edge may reach zero — a zero-sized raster is not an image `BufferedImage` will make.
     */
    private fun scaled(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val type =
            if (image.colorModel.hasAlpha()) {
                BufferedImage.TYPE_INT_ARGB
            } else {
                BufferedImage.TYPE_INT_RGB
            }
        val target = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), type)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(image, 0, 0, target.width, target.height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}

/** The outcome of one conversion: the PNG bytes, or the bounded reason there are none. */
internal sealed interface PrintImagePngResult {
    class Converted(val bytes: ByteArray) : PrintImagePngResult

    data class Failed(val error: PrintImageError) : PrintImagePngResult
}

/**
 * The bounded vocabulary of print-image problems; the names are the persisted error codes.
 *
 * All three are retryable, which is not the same as "likely to heal on their own": a missing image
 * heals when generation catches up, an unreadable or oversized one heals when somebody regenerates
 * it. The job simply keeps its place in the queue until then.
 */
internal enum class PrintImageError {
    /** No image path on the line, or nothing at that path yet. */
    PRINT_IMAGE_MISSING,

    /** No registered reader could decode the file, or the encoder could not write it back. */
    PRINT_IMAGE_UNREADABLE,

    /** Still over the byte budget after the bounded shrink; a human has to look at this one. */
    PRINT_IMAGE_TOO_LARGE,
}

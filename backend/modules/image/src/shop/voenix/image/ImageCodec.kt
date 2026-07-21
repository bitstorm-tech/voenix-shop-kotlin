package shop.voenix.image

import com.luciad.imageio.webp.CompressionType
import com.luciad.imageio.webp.WebPWriteParam
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import io.ktor.http.ContentType
import java.awt.Color
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageInputStream

internal class ImageCodec {
    fun decode(bytes: ByteArray): DecodedImage {
        val format =
            ByteArrayInputStream(bytes).use { input ->
                ImageIO.createImageInputStream(input).use(::inspect)
            }
        return DecodedImage(ImmutableImage.loader().fromBytes(bytes), format)
    }

    fun decode(path: Path): DecodedImage {
        val format = ImageIO.createImageInputStream(path.toFile()).use(::inspect)
        return DecodedImage(ImmutableImage.loader().fromPath(path), format)
    }

    fun write(
        image: ImmutableImage,
        format: Format,
        target: Path,
    ) {
        when (format) {
            Format.JPEG ->
                image
                    .removeTransparency(Color.WHITE)
                    .output(JpegWriter().withCompression(JPEG_QUALITY), target)
            Format.PNG -> image.output(PngWriter().withCompression(PNG_COMPRESSION), target)
            Format.WEBP -> writeWebp(image, target)
        }
    }

    private fun inspect(input: ImageInputStream?): Format {
        requireNotNull(input) { "Image input cannot be opened" }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Unsupported or invalid image data" }
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width.toLong() * height.toLong() <= MAX_DECODED_PIXELS) {
                "Decoded image exceeds 40 megapixels"
            }
            return Format.fromReaderName(reader.formatName)
                ?: error("Unsupported decoded image format: ${reader.formatName}")
        } finally {
            reader.dispose()
        }
    }

    private fun writeWebp(
        image: ImmutableImage,
        target: Path,
    ) {
        val writers = ImageIO.getImageWritersByMIMEType("image/webp")
        check(writers.hasNext()) { "No WebP writer is available" }
        val writer = writers.next()
        try {
            val parameters =
                (writer.defaultWriteParam as WebPWriteParam).apply {
                    compressionType = CompressionType.Lossy
                    compressionQuality = WEBP_QUALITY
                    alphaQuality = WEBP_ALPHA_QUALITY
                    method = WEBP_METHOD
                    threadLevel = WEBP_THREAD_LEVEL
                    useSharpYUV = true
                }
            FileImageOutputStream(target.toFile()).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image.awt(), null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
    }

    data class DecodedImage(val image: ImmutableImage, val format: Format)

    enum class Format(val contentType: ContentType) {
        JPEG(ContentType.Image.JPEG),
        PNG(ContentType.Image.PNG),
        WEBP(ContentType("image", "webp"));

        companion object {
            fun fromFilename(filename: String): Format? =
                when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                    "jpg",
                    "jpeg" -> JPEG
                    "png" -> PNG
                    "webp" -> WEBP
                    else -> null
                }

            fun fromContentType(value: String): Format? =
                when (value.substringBefore(';').trim().lowercase(Locale.ROOT)) {
                    "image/jpeg" -> JPEG
                    "image/png" -> PNG
                    "image/webp" -> WEBP
                    else -> null
                }

            fun fromReaderName(value: String): Format? =
                when (value.lowercase(Locale.ROOT)) {
                    "jpeg",
                    "jpg" -> JPEG
                    "png" -> PNG
                    "webp" -> WEBP
                    else -> null
                }
        }
    }

    private companion object {
        private const val MAX_DECODED_PIXELS = 40_000_000L
        private const val JPEG_QUALITY = 85
        private const val PNG_COMPRESSION = 6
        private const val WEBP_QUALITY = 0.85f
        private const val WEBP_ALPHA_QUALITY = 100
        private const val WEBP_METHOD = 4
        private const val WEBP_THREAD_LEVEL = 1
    }
}

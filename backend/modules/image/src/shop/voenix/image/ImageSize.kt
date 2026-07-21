package shop.voenix.image

internal data class ImageSize(
    val width: Int,
    val height: Int?,
) {
    val cacheKey: String = height?.let { "${width}x$it" } ?: width.toString()

    fun resize(image: com.sksamuel.scrimage.ImmutableImage): com.sksamuel.scrimage.ImmutableImage =
        if (height == null) image.scaleToWidth(width) else image.max(width, height)

    companion object {
        fun parse(value: String): ImageSize? {
            val parts = value.split('x')
            if (parts.size !in 1..2 || parts.any { !it.matches(DIGITS) }) return null
            val width = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_DIMENSION }
            val height = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MAX_DIMENSION }
            return when {
                width == null -> null
                parts.size == 2 && height == null -> null
                else -> ImageSize(width, height)
            }
        }

        private const val MAX_DIMENSION = 4096
        private val DIGITS = Regex("[0-9]+")
    }
}

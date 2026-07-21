package shop.voenix.image

internal enum class ImageVisibility(
    val cacheDirectory: String,
    val cacheControl: String,
) {
    PUBLIC("public", "public, max-age=86400"),
    PRIVATE("private", "private, max-age=3600"),
}

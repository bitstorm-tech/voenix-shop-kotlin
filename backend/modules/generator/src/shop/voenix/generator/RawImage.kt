package shop.voenix.generator

/**
 * Bytes plus the media type they are in — what the visitor uploaded, what the provider returned,
 * and what the response carries.
 *
 * A plain class rather than a data class on purpose: a generated `equals` over a [ByteArray]
 * compares identities, so two images with the same content would compare unequal while looking like
 * a value type. Nothing in this module compares images, so the class provides no equality at all
 * instead of a misleading one.
 */
internal class RawImage(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * The image types this module accepts and serves, lowercase.
 *
 * One list for both directions on purpose: the uploaded image is checked against it, and so is the
 * type fal.ai reports for the generated one. A shop that refuses to take a type it would happily
 * hand back — or the other way round — would be answering the same question twice with two answers.
 */
internal val ALLOWED_IMAGE_CONTENT_TYPES: Set<String> =
    setOf("image/jpeg", "image/png", "image/webp")

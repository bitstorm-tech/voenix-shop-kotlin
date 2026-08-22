package shop.voenix.generator

/**
 * The port that keeps the network out of the service: an image, a prompt, and the shape the result
 * must have go in, the generated image comes out.
 *
 * `null` means "the provider did not deliver an image" — a refused call, an empty answer, an
 * unreadable one, a failed download. The service can do exactly one thing about any of them, so
 * they are one absent case rather than four failure types, and the reason is logged where it is
 * known: in the adapter.
 *
 * [aspectRatio] is passed as the provider's own spelling — `16:9`, `1:1` — because that is what
 * `shop.voenix.article.PrintAspectRatio` stores as its wire value and what fal.ai expects in the
 * request body. Nothing between the article table and the provider has to translate it.
 */
internal fun interface ImageGenerator {
    suspend fun generate(
        image: RawImage,
        prompt: String,
        aspectRatio: String,
    ): RawImage?
}

/**
 * The generator of a deployment in dummy mode: it answers with the uploaded image, unchanged and in
 * its own content type.
 *
 * Dummy mode is a lambda at the composition seam rather than a flag inside the service, so no
 * production code path has to ask whether it is real. The coin check and the spend still run — the
 * point of dummy mode is to avoid provider cost, not to change what the endpoint does.
 *
 * The requested aspect ratio is ignored: reshaping the upload would make dummy mode answer with
 * something the visitor never uploaded, and the byte-for-byte answer is what the composition test
 * recognizes the dummy by.
 */
internal fun dummyImageGenerator(): ImageGenerator = ImageGenerator { image, _, _ -> image }

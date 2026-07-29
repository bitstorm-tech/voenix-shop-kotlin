package shop.voenix.prompt

import kotlinx.serialization.Serializable

/**
 * The answer of an example-image pre-upload: the stored file name a following create or update
 * submits as `exampleImageFilename`.
 *
 * The name is minted by the image storage, and it is the only shape a prompt write accepts — a UUID
 * with dashes and the `.webp` suffix the storage converts every upload to.
 */
@Serializable internal data class ExampleImage(val filename: String)

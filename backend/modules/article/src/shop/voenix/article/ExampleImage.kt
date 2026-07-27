package shop.voenix.article

import kotlinx.serialization.Serializable

/**
 * The answer of an example-image pre-upload: the stored file name a following create or update
 * submits as `exampleImageFilename`.
 *
 * Subcategories and, from the mug slice on, variants upload their example images the same way, so
 * the type lives in the module root next to [ReorderInput] rather than in one of the slices.
 */
@Serializable internal data class ExampleImage(val filename: String)

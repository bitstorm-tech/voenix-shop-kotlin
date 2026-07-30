package shop.voenix.cart

import kotlinx.serialization.Serializable

/**
 * The answer of the print-image pre-upload: the id an add-to-cart request names as its `imageId`.
 *
 * The file name never leaves the module. A client that learned it could ask the file system for
 * somebody else's image; an id, on the other hand, is checked against the stored owner on every
 * use.
 */
@Serializable internal data class PrintImageId(val id: Long)

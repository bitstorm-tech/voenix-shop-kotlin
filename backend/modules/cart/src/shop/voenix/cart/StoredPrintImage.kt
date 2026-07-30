package shop.voenix.cart

/** A print-image row as the repository reads it back: the id it is referenced by and its file. */
internal data class StoredPrintImage(
    val id: Long,
    val filename: String,
)

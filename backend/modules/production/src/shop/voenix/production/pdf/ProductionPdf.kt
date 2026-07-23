package shop.voenix.production.pdf

/**
 * One finished production PDF for one supplier job: the stable producer-facing file name, media
 * type, raw bytes, and their lowercase hex SHA-256 digest for later persistence and delivery.
 */
internal class ProductionPdf(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val sha256: String,
)

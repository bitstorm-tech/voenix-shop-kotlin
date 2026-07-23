package shop.voenix.production

/**
 * Safe, bounded reasons why production-PDF generation failed. Every value is retryable: the
 * condition can heal (an image appears, source data is corrected) and a later attempt may succeed.
 */
public enum class ProductionPdfError {
    /** An item has no production image, or the referenced file does not exist. */
    MISSING_IMAGE,

    /** An item's image file exists but cannot be decoded. */
    UNREADABLE_IMAGE,

    /** An item carries a non-positive quantity or a non-positive measurement override. */
    INVALID_SOURCE,

    /** The renderer failed unexpectedly; details go to the log, never into this result. */
    RENDER_FAILURE,
}

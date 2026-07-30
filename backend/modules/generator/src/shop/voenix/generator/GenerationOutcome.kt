package shop.voenix.generator

/**
 * How one generation ended.
 *
 * The module carries its own outcome type instead of the shared `OperationResult` because three of
 * its answers have no equivalent there: a payment answer (402), an upstream answer (502), and a
 * missing prompt that is not the missing resource of the request path. Building them out of
 * `NotFound` and `Conflict` would give two callers different meanings for the same variant, which
 * is the mistake `CartPromotionResult` avoided.
 */
internal sealed interface GenerationOutcome {
    data class Generated(val image: RawImage) : GenerationOutcome

    /** [message] is a fixed text under the [field] the client has to fix; it never echoes input. */
    data class Invalid(val field: String, val message: String) : GenerationOutcome

    data object InsufficientCoins : GenerationOutcome

    data object PromptUnavailable : GenerationOutcome

    /** The image provider did not deliver an image. */
    data object UpstreamFailure : GenerationOutcome

    data object UnexpectedFailure : GenerationOutcome
}

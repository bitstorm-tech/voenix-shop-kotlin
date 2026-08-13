package shop.voenix.production.fulfillment

/**
 * Typed outcome of a fulfillment PDF download.
 *
 * The four failures are deliberately different things. [NotFound] is the *only* answer for a job id
 * the caller may not read — an unknown id and another supplier's id are indistinguishable, because
 * the difference is exactly what a probe is looking for. The other three are states of an existing,
 * readable job: its artifact does not exist yet, its file vanished from the artifact root, or its
 * bytes no longer hash to the digest recorded at generation time. None of them is the caller's
 * fault and none is a server bug, so each is a conflict with its own stable code.
 */
internal sealed interface FulfillmentArtifactResult {
    /** The verified artifact bytes plus the producer-facing file name to offer them under. */
    class Loaded(val fileName: String, val bytes: ByteArray) : FulfillmentArtifactResult

    /** No such job — or none this caller may read. */
    data object NotFound : FulfillmentArtifactResult

    /** The job exists but its artifact has not been generated yet. */
    data object NotGenerated : FulfillmentArtifactResult

    /** The digest says an artifact exists, but no file does. */
    data object Missing : FulfillmentArtifactResult

    /** The file exists but its bytes are not the generated ones; it is never served. */
    data object DigestMismatch : FulfillmentArtifactResult
}

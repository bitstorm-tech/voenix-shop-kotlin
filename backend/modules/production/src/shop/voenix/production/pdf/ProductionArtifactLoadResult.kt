package shop.voenix.production.pdf

/** Typed outcome of loading a stored production artifact with digest verification. */
internal sealed interface ProductionArtifactLoadResult {
    /** The file exists and its bytes hash to the digest recorded in the database. */
    class Loaded(val bytes: ByteArray) : ProductionArtifactLoadResult

    /** No artifact file exists under the job-scoped path. */
    data object Missing : ProductionArtifactLoadResult

    /** The file exists but its bytes do not hash to the recorded digest. */
    data class DigestMismatch(val actualSha256: String) : ProductionArtifactLoadResult
}

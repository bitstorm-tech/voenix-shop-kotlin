package shop.voenix.image

import org.slf4j.Logger
import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure

/**
 * The rule every module follows for an image a row refers to by name: store it before the row
 * exists, check every submitted name against the storage, and delete a name nothing refers to any
 * more after the write committed.
 *
 * [logger] is the owning service's logger, so a failed cleanup is reported under that module's name
 * — the same reason `Logger.databaseOperation` is an extension on the caller's logger.
 */
public class ExampleImages(
    private val storage: PublicImageStorage,
    private val folder: PublicImageFolder,
    private val logger: Logger,
) {
    /** Stores an uploaded file and answers with the name a following write submits. */
    public suspend fun store(upload: ImageUpload): OperationResult<StoredPublicImage> =
        storage.store(folder, upload)

    /**
     * Whether [filename] names a file this folder holds, reported on [field]. `null` is valid ("no
     * example image").
     *
     * The name has to look like a name the storage mints and the file has to be there; both are
     * client-supplied data, so a rejection is a field error rather than a server failure. A storage
     * that could not answer at all is passed through unchanged, and a malformed name never reaches
     * it.
     */
    public suspend fun checkSubmitted(
        field: String,
        filename: String?,
    ): OperationResult<Unit> =
        when {
            filename == null -> OperationResult.Success(Unit)
            !STORED_IMAGE_FILENAME.matches(filename) -> fieldError(field, MALFORMED_NAME_MESSAGE)
            else ->
                when (val exists = storage.exists(folder, filename)) {
                    is OperationResult.Success ->
                        if (exists.value) {
                            OperationResult.Success(Unit)
                        } else {
                            fieldError(field, MISSING_FILE_MESSAGE)
                        }
                    else -> exists.asFailure()
                }
        }

    /**
     * Removes a file no row referred to when the write committed. A failure is only logged: a row
     * written after that commit can refer to the file again, and a file that stays behind is not
     * the client's problem.
     */
    public suspend fun deleteObsolete(filename: String?) {
        if (filename == null) return
        val result = storage.delete(folder, filename)
        if (result !is OperationResult.Success) {
            logger.warn("Could not delete example image {} in {}: {}", filename, folder, result)
        }
    }
}

private fun fieldError(
    field: String,
    message: String,
): OperationResult<Nothing> = OperationResult.Invalid(mapOf(field to listOf(message)))

/** The shape of every name `PublicImageStorage` mints: a UUID with dashes and WebP. */
private val STORED_IMAGE_FILENAME =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp")

private const val MALFORMED_NAME_MESSAGE =
    "Example image filename must be the name of an uploaded image"
private const val MISSING_FILE_MESSAGE = "Example image does not exist"

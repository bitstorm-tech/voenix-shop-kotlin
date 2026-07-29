package shop.voenix.prompt

import java.util.concurrent.CopyOnWriteArrayList
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.image.StoredPublicImage
import shop.voenix.operation.OperationResult

/**
 * The public image storage of the prompt tests: it remembers file names instead of writing files.
 *
 * The prompt module only decides *when* an image is stored, looked up, or deleted — converting and
 * writing it is the image module's job and is tested there. This double therefore records exactly
 * those decisions, which is what the example-image lifecycle tests assert: which names were
 * touched, which folder they were touched in, and — through [onDelete] — what the database looked
 * like from the outside at the moment a delete was decided.
 *
 * [onDelete] is what makes "deleted only after the commit" provable rather than plausible. A
 * recorded file name says nothing about *when* the call happened; a probe that reads the committed
 * rows through its own connection while the call runs does.
 *
 * [failingDeletes] is the one failure the module has to survive without telling the client: a file
 * it decided to remove is not the client's business, so a delete that fails is only logged.
 */
internal class RecordingPublicImageStorage(
    private val mintedFilenames: List<String> = defaultFilenames,
    private val failingDeletes: Boolean = false,
) : PublicImageStorage {
    private val stored = CopyOnWriteArrayList<String>()

    val deleted: MutableList<String> = CopyOnWriteArrayList()

    /** The folder of every call, in call order, as `operation to folder`. */
    val folders: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Runs while a delete is being decided, with the file name that is about to go. */
    var onDelete: ((String) -> Unit)? = null

    var storeCalls: Int = 0
        private set

    /** The file names that currently exist. */
    val files: List<String>
        get() = stored.toList()

    /** The distinct folders every recorded call went to. */
    fun usedFolders(): Set<String> = folders.mapTo(mutableSetOf()) { (_, folder) -> folder }

    /** Pretends that [filenames] were uploaded before the test started. */
    fun put(vararg filenames: String) {
        stored.addAll(filenames.toList())
    }

    /** Pretends that [filename] disappeared without the module noticing. */
    fun sweep(filename: String) {
        stored.remove(filename)
    }

    override suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage> {
        folders += "store" to folder.toString()
        val filename = mintedFilenames[storeCalls % mintedFilenames.size]
        storeCalls++
        stored.add(filename)
        return OperationResult.Success(StoredPublicImage(filename))
    }

    override suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean> {
        folders += "exists" to folder.toString()
        return OperationResult.Success(filename in stored)
    }

    override suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit> {
        folders += "delete" to folder.toString()
        onDelete?.invoke(filename)
        deleted.add(filename)
        if (failingDeletes) return OperationResult.UnexpectedFailure

        stored.remove(filename)
        return OperationResult.Success(Unit)
    }

    companion object {
        const val FIRST_FILENAME: String = "11111111-1111-4111-8111-111111111111.webp"
        const val SECOND_FILENAME: String = "22222222-2222-4222-8222-222222222222.webp"

        /** The one folder every prompt example image lives in. */
        const val EXAMPLE_IMAGE_FOLDER: String = "prompt-example-images"

        private val defaultFilenames = listOf(FIRST_FILENAME, SECOND_FILENAME)
    }
}

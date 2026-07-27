package shop.voenix.article

import java.util.concurrent.CopyOnWriteArrayList
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.image.StoredPublicImage
import shop.voenix.operation.OperationResult

/**
 * The public image storage of the article tests: it remembers file names instead of writing files.
 *
 * The article module only decides *when* an image is stored, looked up, or deleted — converting and
 * writing it is the image module's job and is tested there. This double therefore records exactly
 * those decisions, which is what the image lifecycle tests assert.
 */
internal class RecordingPublicImageStorage(
    private val mintedFilenames: List<String> = defaultFilenames
) : PublicImageStorage {
    private val stored = CopyOnWriteArrayList<String>()

    val deleted: MutableList<String> = CopyOnWriteArrayList()
    var storeCalls: Int = 0
        private set

    /** The file names that currently exist. */
    val files: List<String>
        get() = stored.toList()

    /** Pretends that [filenames] were uploaded before the test started. */
    fun put(vararg filenames: String) {
        stored.addAll(filenames.toList())
    }

    override suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage> {
        val filename = mintedFilenames[storeCalls % mintedFilenames.size]
        storeCalls++
        stored.add(filename)
        return OperationResult.Success(StoredPublicImage(filename))
    }

    override suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean> = OperationResult.Success(filename in stored)

    override suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit> {
        deleted.add(filename)
        stored.remove(filename)
        return OperationResult.Success(Unit)
    }

    companion object {
        const val FIRST_FILENAME: String = "11111111-1111-4111-8111-111111111111.webp"
        const val SECOND_FILENAME: String = "22222222-2222-4222-8222-222222222222.webp"

        private val defaultFilenames = listOf(FIRST_FILENAME, SECOND_FILENAME)
    }
}

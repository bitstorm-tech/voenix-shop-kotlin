package shop.voenix.image

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class ExampleImagesTest {
    @Test
    fun `a missing name is valid and asks the storage nothing`() {
        val storage = FakePublicImageStorage()
        assertIs<OperationResult.Success<Unit>>(
            runBlocking { exampleImages(storage).checkSubmitted(FIELD, null) }
        )
        assertEquals(0, storage.existsCalls)
    }

    @Test
    fun `a name the storage never mints is rejected without asking the storage`() {
        val storage = FakePublicImageStorage()
        val result =
            assertIs<OperationResult.Invalid>(
                runBlocking { exampleImages(storage).checkSubmitted(FIELD, "not-an-uploaded-name") }
            )
        assertEquals(
            mapOf(FIELD to listOf("Example image filename must be the name of an uploaded image")),
            result.errors,
        )
        assertEquals(0, storage.existsCalls)
    }

    @Test
    fun `a well-shaped name whose file is gone is rejected`() {
        val storage = FakePublicImageStorage()
        val result =
            assertIs<OperationResult.Invalid>(
                runBlocking { exampleImages(storage).checkSubmitted(FIELD, MINTED_NAME) }
            )
        assertEquals(mapOf(FIELD to listOf("Example image does not exist")), result.errors)
    }

    @Test
    fun `a name whose file is there is valid`() {
        val storage = FakePublicImageStorage(files = mutableSetOf(MINTED_NAME))
        assertIs<OperationResult.Success<Unit>>(
            runBlocking { exampleImages(storage).checkSubmitted(FIELD, MINTED_NAME) }
        )
        assertEquals(FOLDER to MINTED_NAME, storage.lastExists)
    }

    @Test
    fun `a storage that cannot answer is passed through unchanged`() {
        val storage = FakePublicImageStorage(existsResult = OperationResult.UnexpectedFailure)
        assertIs<OperationResult.UnexpectedFailure>(
            runBlocking { exampleImages(storage).checkSubmitted(FIELD, MINTED_NAME) }
        )
    }

    @Test
    fun `a stored upload answers with a name a following write may submit`() {
        val storage = FakePublicImageStorage()
        val stored =
            assertIs<OperationResult.Success<StoredPublicImage>>(
                runBlocking {
                    exampleImages(storage).store(ImageUpload(ByteArray(1), "image/webp"))
                }
            )
        assertIs<OperationResult.Success<Unit>>(
            runBlocking { exampleImages(storage).checkSubmitted(FIELD, stored.value.filename) }
        )
    }

    @Test
    fun `deleting nothing deletes nothing`() {
        val storage = FakePublicImageStorage()
        runBlocking { exampleImages(storage).deleteObsolete(null) }
        assertEquals(emptyList(), storage.deleted)
    }

    @Test
    fun `an obsolete name is deleted in this folder`() {
        val storage = FakePublicImageStorage(files = mutableSetOf(MINTED_NAME))
        runBlocking { exampleImages(storage).deleteObsolete(MINTED_NAME) }
        assertEquals(listOf(FOLDER to MINTED_NAME), storage.deleted)
    }

    @Test
    fun `a failing delete does not throw`() {
        val storage = FakePublicImageStorage(deleteResult = OperationResult.UnexpectedFailure)
        runBlocking { exampleImages(storage).deleteObsolete(MINTED_NAME) }
        assertEquals(listOf(FOLDER to MINTED_NAME), storage.deleted)
    }

    /**
     * The regex mirrors what `ImageService.storeWebp` mints (`"${UUID.randomUUID()}.webp"`). If one
     * of the two changes without the other, every submitted name would be rejected as malformed.
     */
    @Test
    fun `every name the storage mints is accepted as well-shaped`() {
        val storage = FakePublicImageStorage()
        repeat(50) {
            val minted = "${UUID.randomUUID()}.webp"
            storage.files += minted
            assertTrue(
                runBlocking { exampleImages(storage).checkSubmitted(FIELD, minted) }
                    is OperationResult.Success,
                minted,
            )
        }
    }

    private fun exampleImages(storage: FakePublicImageStorage): ExampleImages =
        ExampleImages(
            storage,
            PublicImageFolder.of(FOLDER),
            LoggerFactory.getLogger(ExampleImagesTest::class.java),
        )

    private companion object {
        const val FIELD = "exampleImageFilename"
        const val FOLDER = "example-images"
        const val MINTED_NAME = "1b3f5a7c-9d2e-4f60-8a1b-2c3d4e5f6a7b.webp"
    }
}

/** Answers from a set of file names, and records what it was asked. */
private class FakePublicImageStorage(
    val files: MutableSet<String> = mutableSetOf(),
    private val existsResult: OperationResult<Boolean>? = null,
    private val deleteResult: OperationResult<Unit>? = null,
) : PublicImageStorage {
    val deleted: MutableList<Pair<String, String>> = mutableListOf()
    var existsCalls: Int = 0
        private set

    var lastExists: Pair<String, String>? = null
        private set

    override suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage> {
        val filename = "${UUID.randomUUID()}.webp"
        files += filename
        return OperationResult.Success(StoredPublicImage(filename))
    }

    override suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean> {
        existsCalls++
        lastExists = folder.toString() to filename
        return existsResult ?: OperationResult.Success(filename in files)
    }

    override suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit> {
        deleted += folder.toString() to filename
        files -= filename
        return deleteResult ?: OperationResult.Success(Unit)
    }
}

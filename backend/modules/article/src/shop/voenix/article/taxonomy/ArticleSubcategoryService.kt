package shop.voenix.article.taxonomy

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.article.asFailure
import shop.voenix.article.persistence.ArticleSubcategoryDeleteResult
import shop.voenix.article.persistence.ArticleSubcategoryOrderResult
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.article.persistence.ArticleSubcategoryWriteResult
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult

/**
 * The subcategory lifecycle, including the example image that belongs to a subcategory.
 *
 * The image and the row are stored in that order and never in one transaction, so the two failure
 * directions are answered differently on purpose:
 * - a file that no subcategory ends up referring to stays behind as an accepted orphan (the sweep
 *   that removes them is separate, deferred work);
 * - a file that a subcategory *stopped* referring to is deleted after the transaction that removed
 *   the last reference committed, and a failed deletion is only logged. Deleting it earlier could
 *   remove the image of a subcategory whose write was rolled back afterwards. Whether the removed
 *   reference really was the last one is decided by the repository, inside that transaction.
 */
internal class ArticleSubcategoryService(
    private val repository: ArticleSubcategoryRepository,
    private val images: PublicImageStorage,
) : ArticleSubcategoryOperations {
    override suspend fun list(): OperationResult<List<ArticleSubcategory>> =
        databaseOperation("Database error while listing article subcategories") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<ArticleSubcategory> =
        databaseOperation("Database error while reading article subcategory $id") {
            when (val subcategory = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(subcategory)
            }
        }

    override suspend fun create(
        input: ArticleSubcategoryInput
    ): OperationResult<ArticleSubcategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writeChecked(
            message = "Database error while creating article subcategory ${normalized.name}",
            normalized = normalized,
        ) {
            repository.insert(normalized)
        }
    }

    override suspend fun update(
        id: Long,
        input: ArticleSubcategoryInput,
    ): OperationResult<ArticleSubcategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writeChecked(
            message = "Database error while updating article subcategory $id",
            normalized = normalized,
        ) {
            repository.update(id, normalized)
        }
    }

    /**
     * Checks the submitted example image and then runs [write].
     *
     * The check runs on every submitted name, including one the row already stores. That name
     * cannot have been swept — the deferred sweep only removes files no row refers to — so the only
     * reason its file is gone is that another writer replaced it and deleted the file in between.
     * Exempting it would write that dead name back.
     */
    private suspend fun writeChecked(
        message: String,
        normalized: ArticleSubcategoryInput,
        write: suspend () -> ArticleSubcategoryWriteResult,
    ): OperationResult<ArticleSubcategory> =
        when (val exampleImage = checkExampleImage(normalized.exampleImageFilename)) {
            is OperationResult.Success -> databaseOperation(message) { write().toOperationResult() }
            else -> exampleImage.asFailure()
        }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting article subcategory $id") {
            when (val result = repository.delete(id)) {
                is ArticleSubcategoryDeleteResult.Deleted -> {
                    deleteExampleImage(result.exampleImageFilename)
                    OperationResult.Success(Unit)
                }
                ArticleSubcategoryDeleteResult.NotFound -> OperationResult.NotFound
                ArticleSubcategoryDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun reorder(input: ReorderInput): OperationResult<List<ArticleSubcategory>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return databaseOperation("Database error while reordering article subcategories") {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleSubcategoryOrderResult.Reordered ->
                    OperationResult.Success(result.subcategories)
                ArticleSubcategoryOrderResult.NotFound -> OperationResult.NotFound
                ArticleSubcategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    override suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage> =
        when (val stored = images.store(EXAMPLE_IMAGE_FOLDER, upload)) {
            is OperationResult.Success ->
                OperationResult.Success(ExampleImage(stored.value.filename))
            else -> stored.asFailure()
        }

    /**
     * Whether [filename] names a file this module stored. The name has to look like a name the
     * image storage mints and the file has to be there; both are client-supplied data, so a
     * rejection is a field error rather than a server failure.
     */
    private suspend fun checkExampleImage(filename: String?): OperationResult<Unit> =
        when {
            filename == null -> OperationResult.Success(Unit)
            !STORED_IMAGE_FILENAME.matches(filename) ->
                exampleImageError("Example image filename must be the name of an uploaded image")
            else ->
                when (val exists = images.exists(EXAMPLE_IMAGE_FOLDER, filename)) {
                    is OperationResult.Success ->
                        if (exists.value) {
                            OperationResult.Success(Unit)
                        } else {
                            exampleImageError("Example image does not exist")
                        }
                    else -> exists.asFailure()
                }
        }

    /**
     * Removes a file that no subcategory row referred to when the write committed. A subcategory
     * written after that commit can refer to it again, and a failure is not the client's problem
     * either.
     */
    private suspend fun deleteExampleImage(filename: String?) {
        if (filename == null) return
        val result = images.delete(EXAMPLE_IMAGE_FOLDER, filename)
        if (result !is OperationResult.Success) {
            logger.warn(
                "Could not delete article subcategory example image {}: {}",
                filename,
                result,
            )
        }
    }

    private suspend fun ArticleSubcategoryWriteResult.toOperationResult():
        OperationResult<ArticleSubcategory> =
        when (this) {
            is ArticleSubcategoryWriteResult.Stored -> {
                deleteExampleImage(obsoleteExampleImageFilename)
                OperationResult.Success(subcategory)
            }
            ArticleSubcategoryWriteResult.NotFound -> OperationResult.NotFound
            ArticleSubcategoryWriteResult.NameConflict -> OperationResult.Conflict
            ArticleSubcategoryWriteResult.CategoryNotFound ->
                categoryError("Article category does not exist")
            ArticleSubcategoryWriteResult.InUse ->
                categoryError(
                    "Article subcategory is used by articles and cannot be moved to another category"
                )
        }

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ArticleSubcategoryService::class.java)
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/subcategory-example-images")

        /** The shape of every name the public image storage mints: a UUID with dashes and WebP. */
        val STORED_IMAGE_FILENAME =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp")

        fun exampleImageError(message: String): OperationResult<Nothing> =
            OperationResult.Invalid(mapOf("exampleImageFilename" to listOf(message)))

        fun categoryError(message: String): OperationResult<Nothing> =
            OperationResult.Invalid(mapOf("categoryId" to listOf(message)))
    }
}

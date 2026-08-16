package shop.voenix.article.category

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleSubcategoryDeleteResult
import shop.voenix.article.persistence.ArticleSubcategoryOrderResult
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.article.persistence.ArticleSubcategoryWriteResult
import shop.voenix.image.ExampleImages
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure
import shop.voenix.operation.databaseOperation

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
    private val exampleImages = ExampleImages(images, EXAMPLE_IMAGE_FOLDER, logger)

    override suspend fun list(): OperationResult<List<ArticleSubcategory>> =
        logger.databaseOperation(
            "Database error while listing article subcategories",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<ArticleSubcategory> =
        logger.databaseOperation(
            "Database error while reading article subcategory $id",
            OperationResult.UnexpectedFailure,
        ) {
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
        when (
            val exampleImage =
                exampleImages.checkSubmitted(
                    EXAMPLE_IMAGE_FIELD,
                    normalized.exampleImageFilename,
                )
        ) {
            is OperationResult.Success ->
                logger.databaseOperation(message, OperationResult.UnexpectedFailure) {
                    write().toOperationResult()
                }
            else -> exampleImage.asFailure()
        }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting article subcategory $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.delete(id)) {
                is ArticleSubcategoryDeleteResult.Deleted -> {
                    exampleImages.deleteObsolete(result.exampleImageFilename)
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
        return logger.databaseOperation(
            "Database error while reordering article subcategories",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleSubcategoryOrderResult.Reordered ->
                    OperationResult.Success(result.subcategories)
                ArticleSubcategoryOrderResult.NotFound -> OperationResult.NotFound
                ArticleSubcategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    override suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage> =
        when (val stored = exampleImages.store(upload)) {
            is OperationResult.Success ->
                OperationResult.Success(ExampleImage(stored.value.filename))
            else -> stored.asFailure()
        }

    private suspend fun ArticleSubcategoryWriteResult.toOperationResult():
        OperationResult<ArticleSubcategory> =
        when (this) {
            is ArticleSubcategoryWriteResult.Stored -> {
                exampleImages.deleteObsolete(obsoleteExampleImageFilename)
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

    private companion object {
        const val EXAMPLE_IMAGE_FIELD = "exampleImageFilename"

        val logger: Logger = LoggerFactory.getLogger(ArticleSubcategoryService::class.java)
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/subcategory-example-images")

        fun categoryError(message: String): OperationResult<Nothing> =
            OperationResult.Invalid(mapOf("categoryId" to listOf(message)))
    }
}

internal interface ArticleSubcategoryOperations {
    /** Every subcategory, ordered by its category's display order and then by its own. */
    suspend fun list(): OperationResult<List<ArticleSubcategory>>

    suspend fun get(id: Long): OperationResult<ArticleSubcategory>

    /** Creates a subcategory behind the last one of its category. */
    suspend fun create(input: ArticleSubcategoryInput): OperationResult<ArticleSubcategory>

    /**
     * Replaces every stored value, including the category. Two rejections are field errors on
     * `categoryId` rather than conflicts, because both say that the submitted category is not a
     * value this subcategory may take: an unknown category, and a category change while articles
     * use the subcategory.
     */
    suspend fun update(
        id: Long,
        input: ArticleSubcategoryInput,
    ): OperationResult<ArticleSubcategory>

    /**
     * Deletes a subcategory and closes the gap in its category. A subcategory that articles still
     * use produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one subcategory to the place of another and returns the complete new order of their
     * category. Positions count per category, so a target from another category is as unknown as a
     * missing id and produces [OperationResult.NotFound].
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<ArticleSubcategory>>

    /**
     * Stores an example image and returns the file name a following create or update submits. The
     * file is written before any subcategory refers to it, so an upload that is never submitted
     * stays behind as an accepted orphan.
     */
    suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}

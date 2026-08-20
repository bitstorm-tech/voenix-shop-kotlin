package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.db.read
import shop.voenix.prompt.PromptCategoryReference
import shop.voenix.prompt.PublicPrompt

/**
 * The one read the storefront performs. It only reads, so it takes no `PriceCatalog`: the price of
 * a prompt is a *reference* here, resolved by the service for the whole page at once.
 *
 * The query never selects `prompt_text`. That is not an optimization but the contract: the composed
 * generation text is what the shop sells, and the column an anonymous read does not touch cannot
 * end up in an anonymous answer by accident.
 */
internal class PublicPromptRepository(private val database: Database) {
    /**
     * The prompts a customer may see, in display order, each with the reference to its price.
     *
     * One query answers the whole page, whatever the catalog holds: the two category levels come
     * with the rows themselves, because they are also what decides visibility.
     */
    suspend fun list(categoryId: Long?): List<StoredPrompt<PublicPrompt>> = database.read {
        listInTransaction(categoryId)
    }
}

/**
 * The one rule that decides public visibility: the prompt is active and not archived, its category
 * is active, and it either has no subcategory or an active one. The category being *set* is the
 * inner join below — the column is `NOT NULL`, so a prompt always has one.
 *
 * `archived` is the module's soft delete and is therefore part of the same rule as `active`: an
 * archived prompt stays readable by id for the carts and orders that refer to it, but it is not
 * something a customer may still choose.
 */
private fun visiblePromptCondition(categoryId: Long?): Op<Boolean> {
    val visible =
        (Prompts.active eq true) and
            (Prompts.archived eq false) and
            (PromptCategories.active eq true) and
            (Prompts.subcategoryId.isNull() or (PromptSubcategories.active eq true))
    return when (categoryId) {
        null -> visible
        else -> visible and (Prompts.categoryId eq categoryId)
    }
}

/**
 * The visible prompts in display order, each with the reference to the price it owns.
 *
 * The order is `(position, id)` — the module's one global prompt order — with and without the
 * category filter (approved deviation). Legacy sorted the filtered list by subcategory and title
 * instead, which meant the admin's ordering silently stopped applying the moment a customer picked
 * a category. Filtering a list is not reordering it.
 */
private fun listInTransaction(categoryId: Long?): List<StoredPrompt<PublicPrompt>> =
    Prompts.join(
            PromptCategories,
            JoinType.INNER,
            onColumn = Prompts.categoryId,
            otherColumn = PromptCategories.id,
        )
        .join(
            PromptSubcategories,
            JoinType.LEFT,
            onColumn = Prompts.subcategoryId,
            otherColumn = PromptSubcategories.id,
        )
        .select(
            Prompts.id,
            Prompts.position,
            Prompts.title,
            PromptCategories.id,
            PromptCategories.name,
            PromptCategories.position,
            PromptSubcategories.id,
            PromptSubcategories.name,
            PromptSubcategories.position,
            Prompts.exampleImageFilename,
            Prompts.llm,
            Prompts.priceId,
        )
        .where { visiblePromptCondition(categoryId) }
        .orderBy(Prompts.position to SortOrder.ASC, Prompts.id to SortOrder.ASC)
        .map { row ->
            StoredPrompt(
                prompt =
                    PublicPrompt(
                        id = row[Prompts.id].value,
                        position = row[Prompts.position],
                        title = row[Prompts.title],
                        category =
                            PromptCategoryReference(
                                id = row[PromptCategories.id].value,
                                name = row[PromptCategories.name],
                                position = row[PromptCategories.position],
                            ),
                        subcategory = row.toSubcategoryReference(),
                        exampleImageFilename = row[Prompts.exampleImageFilename],
                        llm = row[Prompts.llm],
                        price = null,
                    ),
                priceId = row[Prompts.priceId],
            )
        }

/** The nested subcategory of one row, or `null` for a prompt that sits directly in its category. */
private fun ResultRow.toSubcategoryReference(): PromptCategoryReference? {
    val id = getOrNull(PromptSubcategories.id)?.value ?: return null
    return PromptCategoryReference(
        id = id,
        name = checkNotNull(getOrNull(PromptSubcategories.name)),
        position = checkNotNull(getOrNull(PromptSubcategories.position)),
    )
}

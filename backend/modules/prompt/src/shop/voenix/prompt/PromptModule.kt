package shop.voenix.prompt

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.image.PublicImageStorage
import shop.voenix.pricing.PriceCatalog
import shop.voenix.prompt.category.PromptCategoryInput
import shop.voenix.prompt.category.PromptCategoryOperations
import shop.voenix.prompt.category.PromptCategoryRoutes
import shop.voenix.prompt.category.PromptCategoryService
import shop.voenix.prompt.category.PromptSubcategoryInput
import shop.voenix.prompt.category.PromptSubcategoryOperations
import shop.voenix.prompt.category.PromptSubcategoryRoutes
import shop.voenix.prompt.category.PromptSubcategoryService
import shop.voenix.prompt.persistence.PromptCatalogRepository
import shop.voenix.prompt.persistence.PromptCategoryRepository
import shop.voenix.prompt.persistence.PromptRepository
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.prompt.persistence.PromptSlotVariantRepository
import shop.voenix.prompt.persistence.PromptSubcategoryRepository
import shop.voenix.prompt.persistence.PublicPromptRepository
import shop.voenix.prompt.slot.PromptSlotInput
import shop.voenix.prompt.slot.PromptSlotOperations
import shop.voenix.prompt.slot.PromptSlotRoutes
import shop.voenix.prompt.slot.PromptSlotService
import shop.voenix.prompt.slot.PromptSlotVariantInput
import shop.voenix.prompt.slot.PromptSlotVariantOperations
import shop.voenix.prompt.slot.PromptSlotVariantRoutes
import shop.voenix.prompt.slot.PromptSlotVariantService
import shop.voenix.prompt.slot.PromptSlotVariantUpdate
import shop.voenix.validation.toRequestValidationResult

/**
 * The assembled prompt runtime. The module is split into the sub-packages `slot` (slots and their
 * variants), `category` (the category structure), and `persistence` (Exposed tables, repositories,
 * and the ordering anchors), but it stays one compilation module: the sub-packages organize the
 * files, the module boundary is what `internal` protects.
 *
 * The prompts themselves live in the module root, which is why this handle carries the last two
 * operation interfaces next to the four of the sub-packages: the admin lifecycle of a prompt and
 * the one storefront read of the same rows.
 *
 * [catalog] is the odd one out and belongs here for the same reason the article module keeps its
 * own: it is not a route group but the module's exported capability, and assembling it with the
 * rest is what keeps its repository and its price lookup out of every other module's reach.
 *
 * The seventh constructor parameter is what a module of six route groups and one capability looks
 * like; grouping some of them into a container type would only give the list a shorter name, not a
 * meaning, so the length is suppressed rather than hidden.
 */
@Suppress("LongParameterList")
internal class PromptModule(
    val slots: PromptSlotOperations,
    val slotVariants: PromptSlotVariantOperations,
    val categories: PromptCategoryOperations,
    val subcategories: PromptSubcategoryOperations,
    val prompts: PromptOperations,
    val publicPrompts: PublicPromptOperations,
    val catalog: PromptCatalog,
) {
    fun install(application: Application) {
        PromptSlotRoutes.install(application, slots)
        PromptSlotVariantRoutes.install(application, slotVariants)
        PromptCategoryRoutes.install(application, categories)
        PromptSubcategoryRoutes.install(application, subcategories)
        PromptRoutes.install(application, prompts)
        PublicPromptRoutes.install(application, publicPrompts)
    }
}

internal fun createPromptModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
): PromptModule =
    PromptModule(
        slots = PromptSlotService(PromptSlotRepository(database)),
        slotVariants = PromptSlotVariantService(PromptSlotVariantRepository(database)),
        categories = PromptCategoryService(PromptCategoryRepository(database)),
        subcategories = PromptSubcategoryService(PromptSubcategoryRepository(database)),
        prompts = PromptService(PromptRepository(database, prices), images, prices),
        publicPrompts = PublicPromptService(PublicPromptRepository(database), prices),
        catalog = PromptCatalogService(PromptCatalogRepository(database), prices),
    )

/** The route test seam: installs the slot routes on a caller-provided implementation. */
internal fun Application.installPromptModule(slots: PromptSlotOperations) {
    PromptSlotRoutes.install(this, slots)
}

/** The route test seam: installs the slot-variant routes on a caller-provided implementation. */
internal fun Application.installPromptModule(slotVariants: PromptSlotVariantOperations) {
    PromptSlotVariantRoutes.install(this, slotVariants)
}

/** The route test seam: installs the category routes on a caller-provided implementation. */
internal fun Application.installPromptModule(categories: PromptCategoryOperations) {
    PromptCategoryRoutes.install(this, categories)
}

/** The route test seam: installs the subcategory routes on a caller-provided implementation. */
internal fun Application.installPromptModule(subcategories: PromptSubcategoryOperations) {
    PromptSubcategoryRoutes.install(this, subcategories)
}

/** The route test seam: installs the prompt routes on a caller-provided implementation. */
internal fun Application.installPromptModule(prompts: PromptOperations) {
    PromptRoutes.install(this, prompts)
}

/** The route test seam: installs the storefront route on a caller-provided implementation. */
internal fun Application.installPromptModule(publicPrompts: PublicPromptOperations) {
    PublicPromptRoutes.install(this, publicPrompts)
}

/**
 * Installs the prompt admin routes and the anonymous storefront route, and returns the
 * [PromptCatalog] capability.
 *
 * [images] is where an example image is stored before the prompt that names it is written, looked
 * up while that prompt is written, and deleted once no prompt names it any more. [prices] is the
 * capability that writes a prompt's price into the prompt's own transaction, so that neither half
 * can survive the rollback of the other.
 *
 * The composition root discards the returned capability for now, exactly as it discards Article's
 * `ArticleCatalog` and Promotion's `PromotionCodes`: no migrated module composes a generation
 * prompt or prices one yet. The Generator and Cart migrations bind it.
 */
public fun Application.installPromptModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
): PromptCatalog {
    val module = createPromptModule(database, images, prices)
    module.install(this)
    return module.catalog
}

public fun RequestValidationConfig.validatePromptRequests() {
    validate<PromptSlotInput> { input -> input.toRequestValidationResult() }
    validate<PromptSlotVariantInput> { input -> input.toRequestValidationResult() }
    validate<PromptSlotVariantUpdate> { input -> input.toRequestValidationResult() }
    validate<PromptCategoryInput> { input -> input.toRequestValidationResult() }
    validate<PromptSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<PromptInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}

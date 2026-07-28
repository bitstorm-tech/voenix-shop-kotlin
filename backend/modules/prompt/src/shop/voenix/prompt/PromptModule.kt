package shop.voenix.prompt

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.prompt.category.PromptCategoryInput
import shop.voenix.prompt.category.PromptCategoryOperations
import shop.voenix.prompt.category.PromptCategoryRoutes
import shop.voenix.prompt.category.PromptCategoryService
import shop.voenix.prompt.category.PromptSubcategoryInput
import shop.voenix.prompt.category.PromptSubcategoryOperations
import shop.voenix.prompt.category.PromptSubcategoryRoutes
import shop.voenix.prompt.category.PromptSubcategoryService
import shop.voenix.prompt.persistence.PromptCategoryRepository
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.prompt.persistence.PromptSlotVariantRepository
import shop.voenix.prompt.persistence.PromptSubcategoryRepository
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
 * The prompts themselves are migrated in the following slice; they become a further operation
 * interface of this handle and further routes of [install].
 */
internal class PromptModule(
    val slots: PromptSlotOperations,
    val slotVariants: PromptSlotVariantOperations,
    val categories: PromptCategoryOperations,
    val subcategories: PromptSubcategoryOperations,
) {
    fun install(application: Application) {
        PromptSlotRoutes.install(application, slots)
        PromptSlotVariantRoutes.install(application, slotVariants)
        PromptCategoryRoutes.install(application, categories)
        PromptSubcategoryRoutes.install(application, subcategories)
    }
}

internal fun createPromptModule(database: Database): PromptModule =
    PromptModule(
        slots = PromptSlotService(PromptSlotRepository(database)),
        slotVariants = PromptSlotVariantService(PromptSlotVariantRepository(database)),
        categories = PromptCategoryService(PromptCategoryRepository(database)),
        subcategories = PromptSubcategoryService(PromptSubcategoryRepository(database)),
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

/**
 * Installs the prompt admin routes.
 *
 * The slice that migrates the prompts themselves adds what the module needs from other modules —
 * the public image storage of the example images and the pricing capability that writes a prompt's
 * price into the prompt's own transaction — and returns the exported `PromptCatalog` capability
 * that the future Generator and Cart migrations consume. Until then the routes need a database and
 * nothing else, and an installation function that pretended otherwise would be a parameter no
 * caller can use.
 */
public fun Application.installPromptModule(database: Database) {
    createPromptModule(database).install(this)
}

public fun RequestValidationConfig.validatePromptRequests() {
    validate<PromptSlotInput> { input -> input.toRequestValidationResult() }
    validate<PromptSlotVariantInput> { input -> input.toRequestValidationResult() }
    validate<PromptSlotVariantUpdate> { input -> input.toRequestValidationResult() }
    validate<PromptCategoryInput> { input -> input.toRequestValidationResult() }
    validate<PromptSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}

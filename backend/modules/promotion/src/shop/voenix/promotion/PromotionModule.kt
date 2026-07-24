package shop.voenix.promotion

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

internal class PromotionModule(val operations: PromotionOperations) {
    fun install(application: Application): Unit = PromotionRoutes.install(application, operations)
}

internal fun createPromotionModule(database: Database): PromotionModule =
    PromotionModule(PromotionService(PromotionRepository(database)))

internal fun Application.installPromotionModule(promotions: PromotionOperations): Unit =
    PromotionRoutes.install(this, promotions)

public fun Application.installPromotionModule(database: Database): Unit =
    createPromotionModule(database).install(this)

public fun RequestValidationConfig.validatePromotionRequests() {
    validate<PromotionInput> { input -> input.toRequestValidationResult() }
}

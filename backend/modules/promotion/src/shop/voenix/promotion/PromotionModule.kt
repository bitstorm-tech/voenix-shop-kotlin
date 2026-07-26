package shop.voenix.promotion

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import java.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

internal class PromotionModule(
    val operations: PromotionOperations,
    val codes: PromotionCodes,
) {
    fun install(application: Application): Unit = PromotionRoutes.install(application, operations)
}

internal fun createPromotionModule(
    database: Database,
    clock: Clock,
): PromotionModule {
    val service = PromotionService(PromotionRepository(database), clock)
    return PromotionModule(operations = service, codes = service)
}

internal fun Application.installPromotionModule(promotions: PromotionOperations): Unit =
    PromotionRoutes.install(this, promotions)

/**
 * Installs the admin routes and returns the coupon-code capability. The composition root does not
 * bind the capability yet; the Cart, Order, and Checkout migrations will. The [clock] drives the
 * activity-window check of [PromotionCodes.validate].
 */
public fun Application.installPromotionModule(
    database: Database,
    clock: Clock = Clock.systemUTC(),
): PromotionCodes {
    val module = createPromotionModule(database, clock)
    module.install(this)
    return module.codes
}

public fun RequestValidationConfig.validatePromotionRequests() {
    validate<PromotionInput> { input -> input.toRequestValidationResult() }
}

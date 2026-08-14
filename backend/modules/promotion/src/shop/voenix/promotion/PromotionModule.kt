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
    fun install(application: Application): Unit = application.installPromotionRoutes(operations)
}

internal fun createPromotionModule(
    database: Database,
    clock: Clock,
): PromotionModule {
    val service = PromotionService(PromotionRepository(database), clock)
    return PromotionModule(operations = service, codes = service)
}

/**
 * Installs the admin routes and returns the coupon-code capability. The composition root binds it
 * to the cart module, which validates the code a customer enters and renders the promotion a cart
 * has stored, to the order module, which redeems it when a payment is confirmed, and to the
 * checkout, which reserves it. The [clock] drives the activity-window check of
 * [PromotionCodes.validate] and [PromotionCodes.reserve].
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

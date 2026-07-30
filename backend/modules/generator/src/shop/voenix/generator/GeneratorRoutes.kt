package shop.voenix.generator

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.magiccoins.magicCoinsOwner

/**
 * The HTTP surface of the generator: one route, and the one `when` that turns an outcome into a
 * status.
 *
 * The subtree hangs below `/api/generator` and carries the guest-capable protection, because the
 * endpoint serves visitors without an account while still costing money on every call. The legacy
 * application left this endpoint unprotected; a request without a CSRF token is now rejected before
 * any operation runs.
 *
 * A success is answered with the raw image bytes and nothing else — no JSON envelope, no
 * `Content-Disposition` — because the frontend reads the response as a `Blob`.
 */
internal object GeneratorRoutes {
    fun install(
        application: Application,
        generator: GeneratorOperations,
        guestTokens: GuestTokens,
    ) {
        application.routing {
            route(BASE_PATH) {
                installGuestCapableRouteProtection()

                post("/generate") {
                    val upload = call.receiveGenerationUpload()
                    val owner = call.magicCoinsOwner(guestTokens)
                    call.respondOutcome(generator.generate(owner, upload))
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondOutcome(outcome: GenerationOutcome) {
        when (outcome) {
            is GenerationOutcome.Generated ->
                respondBytes(outcome.image.bytes, ContentType.parse(outcome.image.contentType))
            is GenerationOutcome.Invalid ->
                respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "Validation failed",
                        mapOf(outcome.field to listOf(outcome.message)),
                    ),
                )
            GenerationOutcome.InsufficientCoins ->
                respond(
                    HttpStatusCode.PaymentRequired,
                    ApiError("Not enough Magic Coins", code = INSUFFICIENT_COINS_CODE),
                )
            GenerationOutcome.PromptUnavailable ->
                respond(HttpStatusCode.NotFound, ApiError("Prompt not found"))
            GenerationOutcome.UpstreamFailure ->
                respond(HttpStatusCode.BadGateway, ApiError("Generator API error"))
            GenerationOutcome.UnexpectedFailure ->
                respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        }
    }

    private const val BASE_PATH = "/api/generator"

    /** The storefront reads this code from `details.code` to show its own out-of-coins dialog. */
    private const val INSUFFICIENT_COINS_CODE = "INSUFFICIENT_MAGIC_COINS"
}

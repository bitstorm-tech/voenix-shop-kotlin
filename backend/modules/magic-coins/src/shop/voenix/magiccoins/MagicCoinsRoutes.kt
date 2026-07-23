package shop.voenix.magiccoins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object MagicCoinsRoutes {
    fun install(
        application: Application,
        magicCoins: MagicCoinsOperations,
        guestTokens: GuestTokens,
    ) {
        application.routing {
            get("/api/magic-coins/balance") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                when (val result = magicCoins.balance(call.owner(guestTokens))) {
                    is OperationResult.Success ->
                        call.respond(MagicCoinsBalanceResponse(result.value))
                    else ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError("Internal server error"),
                        )
                }
            }
        }
    }

    private fun ApplicationCall.owner(guestTokens: GuestTokens): MagicCoinsOwner {
        val userId = currentUserSession()?.userId?.toLongOrNull()?.takeIf { it > 0 }
        return if (userId != null) {
            MagicCoinsOwner.User(userId)
        } else {
            MagicCoinsOwner.Guest(guestTokens.getOrCreate(this))
        }
    }
}

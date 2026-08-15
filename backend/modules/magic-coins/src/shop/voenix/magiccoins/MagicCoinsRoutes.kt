package shop.voenix.magiccoins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.GuestTokens
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal fun Application.installMagicCoinsRoutes(
    magicCoins: MagicCoinsOperations,
    guestTokens: GuestTokens,
) {
    routing {
        get("/api/magic-coins/balance") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (val result = magicCoins.balance(call.magicCoinsOwner(guestTokens))) {
                is OperationResult.Success -> call.respond(MagicCoinsBalanceResponse(result.value))
                else ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiError("Internal server error"),
                    )
            }
        }
    }
}

@Serializable internal data class MagicCoinsBalanceResponse(val balance: Int)

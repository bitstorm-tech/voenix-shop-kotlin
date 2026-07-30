package shop.voenix.auth

import io.ktor.server.application.install
import io.ktor.server.routing.Route

/**
 * Protects a subtree that serves guests and logged-in users alike. Every request passes
 * authorization, mutating requests must still carry a valid CSRF session and header pair; a request
 * with a user session must additionally use a token minted for that user.
 */
public fun Route.installGuestCapableRouteProtection(): Unit {
    install(GuestCapableRouteProtection.plugin)
}

private object GuestCapableRouteProtection {
    val plugin =
        RouteProtection.failClosedPlugin(
            name = "GuestCapableRouteProtection",
            authorize = { true },
            requireCsrf = AuthModule::requireGuestCapableCsrf,
        )
}

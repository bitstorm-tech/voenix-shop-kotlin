package shop.voenix.auth

import io.ktor.server.application.install
import io.ktor.server.routing.Route

public fun Route.installAuthenticatedRouteProtection(): Unit {
    install(AuthenticatedRouteProtection.plugin)
}

private object AuthenticatedRouteProtection {
    val plugin =
        RouteProtection.failClosedPlugin(
            name = "AuthenticatedRouteProtection",
            authorize = AuthModule::requireAuthenticated,
        )
}

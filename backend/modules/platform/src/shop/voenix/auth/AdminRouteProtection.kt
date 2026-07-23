package shop.voenix.auth

import io.ktor.server.application.install
import io.ktor.server.routing.Route

public fun Route.installAdminRouteProtection(): Unit {
    install(AdminRouteProtection.plugin)
}

private object AdminRouteProtection {
    val plugin =
        RouteProtection.failClosedPlugin(
            name = "AdminRouteProtection",
            authorize = AuthModule::requireAdmin,
        )
}

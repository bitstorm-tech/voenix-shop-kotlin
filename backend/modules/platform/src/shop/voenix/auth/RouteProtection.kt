package shop.voenix.auth

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route
import shop.voenix.http.BeforeRouteHandler

internal object RouteProtection {
    fun failClosedPlugin(
        name: String,
        authorize: suspend (ApplicationCall) -> Boolean,
        requireCsrf: suspend (ApplicationCall) -> Boolean = AuthModule::requireCsrf,
    ): RouteScopedPlugin<Unit> =
        createRouteScopedPlugin(name) {
            on(BeforeRouteHandler) { call ->
                if (call.isHandled) return@on
                if (!authorize(call)) return@on
                if (call.request.httpMethod in csrfProtectedMethods && !requireCsrf(call)) {
                    return@on
                }
            }
        }

    private val csrfProtectedMethods =
        setOf(
            HttpMethod.Post,
            HttpMethod.Put,
            HttpMethod.Patch,
            HttpMethod.Delete,
        )
}

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

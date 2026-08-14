package shop.voenix.auth

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route

/**
 * Builds a route-scoped protection plugin that runs before the route handler and fails closed: the
 * handler below only runs when [authorize] passed and, for a mutating method, [requireCsrf] passed
 * as well.
 */
internal fun failClosedPlugin(
    name: String,
    authorize: suspend (ApplicationCall) -> Boolean,
    requireCsrf: suspend (ApplicationCall) -> Boolean = ::requireCsrf,
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

private object BeforeRouteHandler : Hook<suspend (ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) { handler(call) }
    }
}

public fun Route.installAdminRouteProtection(): Unit {
    install(adminRouteProtection)
}

private val adminRouteProtection =
    failClosedPlugin(
        name = "AdminRouteProtection",
        authorize = ::requireAdmin,
    )

public fun Route.installAuthenticatedRouteProtection(): Unit {
    install(authenticatedRouteProtection)
}

private val authenticatedRouteProtection =
    failClosedPlugin(
        name = "AuthenticatedRouteProtection",
        authorize = ::requireAuthenticated,
    )

/**
 * Protects a subtree that serves guests and logged-in users alike. Every request passes
 * authorization, mutating requests must still carry a valid CSRF session and header pair; a request
 * with a user session must additionally use a token minted for that user.
 */
public fun Route.installGuestCapableRouteProtection(): Unit {
    install(guestCapableRouteProtection)
}

private val guestCapableRouteProtection =
    failClosedPlugin(
        name = "GuestCapableRouteProtection",
        authorize = { true },
        requireCsrf = ::requireGuestCapableCsrf,
    )

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

    private object BeforeRouteHandler : Hook<suspend (ApplicationCall) -> Unit> {
        override fun install(
            pipeline: ApplicationCallPipeline,
            handler: suspend (ApplicationCall) -> Unit,
        ) {
            pipeline.intercept(ApplicationCallPipeline.Call) { handler(call) }
        }
    }
}

public fun Route.installAdminRouteProtection() {
    install(AdminRouteProtection.plugin)
}

private object AdminRouteProtection {
    val plugin =
        RouteProtection.failClosedPlugin(
            name = "AdminRouteProtection",
            authorize = AuthModule::requireAdmin,
        )
}

public fun Route.installAuthenticatedRouteProtection() {
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
public fun Route.installGuestCapableRouteProtection() {
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

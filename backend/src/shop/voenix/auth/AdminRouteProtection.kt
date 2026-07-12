package shop.voenix.auth

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod

internal val AdminRouteProtection =
    createRouteScopedPlugin("AdminRouteProtection") {
        on(BeforeRouteHandler) { call ->
            if (call.isHandled) return@on
            if (!ApplicationAuth.requireAdmin(call)) return@on
            if (
                call.request.httpMethod in csrfProtectedMethods &&
                    !ApplicationAuth.requireCsrf(call)
            ) {
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

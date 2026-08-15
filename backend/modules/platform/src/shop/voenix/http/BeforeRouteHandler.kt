package shop.voenix.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call

/**
 * Runs a plugin's handler in the `Call` phase, the phase routing itself runs in, so a handler that
 * answers the call leaves it handled and Ktor skips the route handler below.
 */
internal object BeforeRouteHandler : Hook<suspend (ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) { handler(call) }
    }
}

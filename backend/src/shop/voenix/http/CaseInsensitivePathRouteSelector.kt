package shop.voenix.http

import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

internal class CaseInsensitivePathRouteSelector(
    path: String,
) : RouteSelector() {
    private val segments = path.trim('/').split('/').filter(String::isNotEmpty)

    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation {
        if (context.segments.size < segmentIndex + segments.size) {
            return RouteSelectorEvaluation.FailedPath
        }
        val matches =
            segments.indices.all { offset ->
                segments[offset].equals(context.segments[segmentIndex + offset], ignoreCase = true)
            }
        if (!matches) return RouteSelectorEvaluation.FailedPath
        return RouteSelectorEvaluation.Success(
            quality = RouteSelectorEvaluation.qualityConstant,
            segmentIncrement = segments.size,
        )
    }
}

fun Route.caseInsensitiveRoute(
    path: String,
    build: Route.() -> Unit,
): Route = createChild(CaseInsensitivePathRouteSelector(path)).apply(build)

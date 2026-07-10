package shop.voenix.country

import io.ktor.http.parametersOf
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteParameterComponent
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

class LongPathSegmentRouteSelector(
    override val name: String,
) : RouteSelector(),
    RouteParameterComponent {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation {
        val segment = context.segments.getOrNull(segmentIndex) ?: return RouteSelectorEvaluation.FailedPath
        val value = segment.trim().toLongOrNull() ?: return RouteSelectorEvaluation.FailedPath
        return RouteSelectorEvaluation.Success(
            quality = RouteSelectorEvaluation.qualityPathParameter,
            parameters = parametersOf(name, value.toString()),
            segmentIncrement = 1,
        )
    }
}

fun Route.longPathSegment(
    name: String,
    build: Route.() -> Unit,
): Route = createChild(LongPathSegmentRouteSelector(name)).apply(build)

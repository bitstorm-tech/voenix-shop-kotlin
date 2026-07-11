package shop.voenix.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString

internal object HttpProblemResponses {
    suspend fun respond(
        call: ApplicationCall,
        status: HttpStatusCode,
        section: String,
        title: String,
    ) {
        val problem =
            HttpProblemDetails(
                type = "https://tools.ietf.org/html/rfc9110#section-$section",
                title = title,
                status = status.value,
                traceId = Traceparent.continueOrCreate(call.request.headers["traceparent"]),
            )
        call.respondText(
            text = HttpRuntime.json.encodeToString(problem),
            contentType = ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
            status = status,
        )
    }
}

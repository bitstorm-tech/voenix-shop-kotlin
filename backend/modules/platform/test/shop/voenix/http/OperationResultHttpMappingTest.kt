package shop.voenix.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import shop.voenix.operation.OperationResult

/**
 * The shared route mapping from [OperationResult] to an HTTP answer.
 *
 * Every test drives a real route through Ktor's test host, so the assertions cover the status code
 * *and* the serialized body — the two things a client sees.
 */
internal class OperationResultHttpMappingTest {
    @Test
    fun `a success answers 200 with the value`() = testApplication {
        application { installResultRoute(OperationResult.Success(Payload("green"))) }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"label":"green"}""", response.bodyAsText())
    }

    @Test
    fun `a success answers with the configured success status`() = testApplication {
        application {
            installResultRoute(
                OperationResult.Success(Payload("created")),
                successStatus = HttpStatusCode.Created,
            )
        }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"label":"created"}""", response.bodyAsText())
    }

    @Test
    fun `a not found answers 404 with the configured error`() = testApplication {
        application { installResultRoute(OperationResult.NotFound) }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"message":"Widget not found","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `a conflict answers 409 with the configured error`() = testApplication {
        application { installResultRoute(OperationResult.Conflict) }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"message":"Widget already exists","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `an invalid result answers 400 with the field errors`() = testApplication {
        application {
            installResultRoute(
                OperationResult.Invalid(mapOf("label" to listOf("Label is required")))
            )
        }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            """{"message":"Validation failed","errors":{"label":["Label is required"]}}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `an unexpected failure answers 500 without implementation details`() = testApplication {
        application { installResultRoute(OperationResult.UnexpectedFailure) }

        val response = client.get("/result")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("""{"message":"Internal server error","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `an unreachable conflict fails with its reason instead of answering`() = testApplication {
        application {
            installFailureReasonRoute(
                OperationResult.Conflict,
                WIDGET_RESPONSES.copy(
                    conflict = ConflictHandling.Unreachable("Widgets cannot conflict")
                ),
            )
        }

        assertEquals("Widgets cannot conflict", client.get("/failure").bodyAsText())
    }

    @Test
    fun `an unreachable invalid result fails with its reason instead of answering`() =
        testApplication {
            application {
                installFailureReasonRoute(
                    OperationResult.Invalid(mapOf("label" to listOf("Label is required"))),
                    WIDGET_RESPONSES.copy(
                        invalid = InvalidHandling.Unreachable("Widget reads cannot be invalid")
                    ),
                )
            }

            assertEquals("Widget reads cannot be invalid", client.get("/failure").bodyAsText())
        }

    @Test
    fun `respondFailure maps every failure variant like respondResult`() = testApplication {
        application {
            installHttpRuntime()
            routing {
                route("/failure") {
                    get("/not-found") {
                        call.respondFailure(OperationResult.NotFound, WIDGET_RESPONSES)
                    }
                    get("/conflict") {
                        call.respondFailure(OperationResult.Conflict, WIDGET_RESPONSES)
                    }
                    get("/invalid") {
                        call.respondFailure(
                            OperationResult.Invalid(mapOf("label" to listOf("Label is required"))),
                            WIDGET_RESPONSES,
                        )
                    }
                    get("/unexpected") {
                        call.respondFailure(OperationResult.UnexpectedFailure, WIDGET_RESPONSES)
                    }
                }
            }
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/failure/not-found").status)
        assertEquals(
            """{"message":"Widget not found","errors":{}}""",
            client.get("/failure/not-found").bodyAsText(),
        )
        assertEquals(HttpStatusCode.Conflict, client.get("/failure/conflict").status)
        assertEquals(
            """{"message":"Widget already exists","errors":{}}""",
            client.get("/failure/conflict").bodyAsText(),
        )
        assertEquals(HttpStatusCode.BadRequest, client.get("/failure/invalid").status)
        assertEquals(
            """{"message":"Validation failed","errors":{"label":["Label is required"]}}""",
            client.get("/failure/invalid").bodyAsText(),
        )
        assertEquals(HttpStatusCode.InternalServerError, client.get("/failure/unexpected").status)
        assertEquals(
            """{"message":"Internal server error","errors":{}}""",
            client.get("/failure/unexpected").bodyAsText(),
        )
    }

    @Test
    fun `respondFailure refuses a success result`() = testApplication {
        application {
            installFailureReasonRoute(OperationResult.Success(Payload("green")), WIDGET_RESPONSES)
        }

        assertEquals(
            "A success result cannot be handled as a failure",
            client.get("/failure").bodyAsText(),
        )
    }

    @Test
    fun `a numeric path parameter reaches the route`() = testApplication {
        application { installIdRoute() }

        val response = client.get("/widgets/42")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("id=42", response.bodyAsText())
    }

    @Test
    fun `zero and negative ids parse and are left to the operation`() = testApplication {
        application { installIdRoute() }

        assertEquals("id=0", client.get("/widgets/0").bodyAsText())
        assertEquals("id=-7", client.get("/widgets/-7").bodyAsText())
    }

    @Test
    fun `a non-numeric id answers with the configured status and body`() = testApplication {
        application { installIdRoute() }

        val response = client.get("/widgets/abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"message":"Invalid widget id","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `an id above Long range is malformed, not truncated`() = testApplication {
        application { installIdRoute() }

        val response = client.get("/widgets/9223372036854775808")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"message":"Invalid widget id","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `a missing parameter is malformed as well`() = testApplication {
        application {
            installHttpRuntime()
            routing {
                // No `{id}` in the path at all: the parameter is simply absent.
                get("/widgets") {
                    val id = call.widgetIdOrRespond() ?: return@get
                    call.respondText("id=$id")
                }
            }
        }

        val response = client.get("/widgets")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"message":"Invalid widget id","errors":{}}""", response.bodyAsText())
    }

    @Test
    fun `a malformed id may use another status than 400`() = testApplication {
        application {
            installHttpRuntime()
            routing {
                get("/widgets/{id}") {
                    val id =
                        call.longPathParameterOrRespond(
                            "id",
                            HttpStatusCode.NotFound,
                            ApiError("Widget not found"),
                        ) ?: return@get
                    call.respondText("id=$id")
                }
            }
        }

        val response = client.get("/widgets/abc")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"message":"Widget not found","errors":{}}""", response.bodyAsText())
    }

    /** A route that answers one fixed [result] through the shared mapping. */
    private fun Application.installResultRoute(
        result: OperationResult<Payload>,
        mapping: OperationResultHttpMapping = WIDGET_RESPONSES,
        successStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        installHttpRuntime()
        routing { get("/result") { call.respondResult(result, mapping, successStatus) } }
    }

    /**
     * A route that reports the message of the [IllegalStateException] the mapping raises, so an
     * unreachable branch is told apart from an ordinary `500`.
     */
    private fun Application.installFailureReasonRoute(
        result: OperationResult<*>,
        mapping: OperationResultHttpMapping,
    ) {
        installHttpRuntime()
        routing {
            get("/failure") {
                val reason =
                    try {
                        call.respondFailure(result, mapping)
                        "no failure was raised"
                    } catch (exception: IllegalStateException) {
                        exception.message.orEmpty()
                    }
                call.respondText(reason)
            }
        }
    }

    /** A route that only echoes the parsed path id. */
    private fun Application.installIdRoute() {
        installHttpRuntime()
        routing {
            get("/widgets/{id}") {
                val id = call.widgetIdOrRespond() ?: return@get
                call.respondText("id=$id")
            }
        }
    }

    @Serializable private data class Payload(val label: String)

    private companion object {
        val WIDGET_RESPONSES =
            OperationResultHttpMapping(
                notFound = ApiError("Widget not found"),
                conflict = ConflictHandling.Respond(ApiError("Widget already exists")),
            )
    }
}

/** The domain-named forwarder a route file would declare, exercised here as the real call site. */
private suspend fun ApplicationCall.widgetIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid widget id"))

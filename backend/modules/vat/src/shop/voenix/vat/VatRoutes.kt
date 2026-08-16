package shop.voenix.vat

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

internal fun Application.installVatRoutes(vats: VatOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/vat") {
                installAdminRouteProtection()

                get { call.respondResult(vats.list(), VAT_RESPONSES) }

                post {
                    val input = call.receive<VatInput>()
                    when (val result = vats.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "/api/admin/vat/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result, VAT_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.vatIdOrRespond() ?: return@get
                        call.respondResult(vats.get(id), VAT_RESPONSES)
                    }

                    put {
                        val id = call.vatIdOrRespond() ?: return@put
                        call.respondResult(
                            vats.update(id, call.receive<VatInput>()),
                            VAT_RESPONSES,
                        )
                    }

                    delete {
                        val id = call.vatIdOrRespond() ?: return@delete
                        when (val result = vats.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            OperationResult.Conflict ->
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    ApiError("VAT is in use"),
                                )
                            else -> call.respondFailure(result, VAT_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

@Serializable
internal data class VatInput(
    val name: String? = null,
    val percent: Int? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val inputPercent = percent
        if (inputPercent == null) {
            put("percent", listOf("Percent is required"))
        } else if (inputPercent !in MINIMUM_PERCENT..MAXIMUM_PERCENT) {
            put("percent", listOf("Percent must be between 0 and 100"))
        }
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MINIMUM_PERCENT = 0
        private const val MAXIMUM_PERCENT = 100
    }
}

private val VAT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("VAT not found"),
        conflict = ConflictHandling.Respond(ApiError("VAT entry already exists")),
    )

private suspend fun ApplicationCall.vatIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid VAT id"))

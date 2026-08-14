package shop.voenix.production

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
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

internal fun Application.installDestinationRoutes(destinations: ProductionDestinationOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/production/destinations") {
                installAdminRouteProtection()

                get { call.respondResult(destinations.list()) }

                post {
                    val input = call.receive<ProductionDestinationInput>()
                    when (val result = destinations.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "/api/admin/production/destinations/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.destinationIdOrRespond() ?: return@get
                        call.respondResult(destinations.get(id))
                    }

                    put {
                        val id = call.destinationIdOrRespond() ?: return@put
                        call.respondResult(
                            destinations.update(id, call.receive<ProductionDestinationInput>())
                        )
                    }

                    delete {
                        val id = call.destinationIdOrRespond() ?: return@delete
                        when (val result = destinations.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            else -> call.respondFailure(result)
                        }
                    }
                }
            }
        }
    }
}

/** Admin API view of a production destination. The SFTP password is write-only and never here. */
@Serializable
internal data class ProductionDestination(
    val id: Long,
    val supplierId: Long,
    val channel: String,
    val label: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
    val notificationEmail: String?,
    val notificationName: String?,
)

@Serializable
internal data class ProductionDestinationInput(
    val supplierId: Long? = null,
    val channel: String? = null,
    val label: String? = null,
    val enabled: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val hostKeyFingerprint: String? = null,
    val remotePath: String? = null,
    val timeoutSeconds: Int? = null,
    val notificationEmail: String? = null,
    val notificationName: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (supplierId == null) {
            put("supplierId", listOf("SupplierId is required"))
        } else if (supplierId <= 0) {
            put("supplierId", listOf("SupplierId must be positive"))
        }

        if (channel.isNullOrBlank()) {
            put("channel", listOf("Channel is required"))
        } else if (channel.trim() !in SUPPORTED_CHANNELS) {
            put("channel", listOf("Channel must be one of: ${SUPPORTED_CHANNELS.joinToString()}"))
        }

        validateRequiredText("label", "Label", label)
        validateRequiredText("host", "Host", host)
        validateRequiredText("username", "Username", username)
        validateRequiredText("hostKeyFingerprint", "HostKeyFingerprint", hostKeyFingerprint)

        if (port != null && port !in MINIMUM_PORT..MAXIMUM_PORT) {
            put("port", listOf("Port must be between $MINIMUM_PORT and $MAXIMUM_PORT"))
        }

        if (password != null && password.length > MAXIMUM_TEXT_LENGTH) {
            put("password", listOf("Password must be at most $MAXIMUM_TEXT_LENGTH characters"))
        }

        if (!remotePath.isNullOrBlank() && remotePath.trim().length > MAXIMUM_PATH_LENGTH) {
            put(
                "remotePath",
                listOf("RemotePath must be at most $MAXIMUM_PATH_LENGTH characters"),
            )
        }

        if (timeoutSeconds == null) {
            put("timeoutSeconds", listOf("TimeoutSeconds is required"))
        } else if (timeoutSeconds !in MINIMUM_TIMEOUT_SECONDS..MAXIMUM_TIMEOUT_SECONDS) {
            put(
                "timeoutSeconds",
                listOf(
                    "TimeoutSeconds must be between $MINIMUM_TIMEOUT_SECONDS and " +
                        "$MAXIMUM_TIMEOUT_SECONDS"
                ),
            )
        }

        validateEmail(notificationEmail)
        validateOptionalLength("notificationName", "NotificationName", notificationName)
    }

    /** Keeps the write-only SFTP password out of logs and error messages. */
    override fun toString(): String =
        "ProductionDestinationInput(supplierId=$supplierId, channel=$channel, label=$label, " +
            "enabled=$enabled, host=$host, port=$port, username=$username, " +
            "password=${if (password == null) "null" else "[redacted]"}, " +
            "hostKeyFingerprint=$hostKeyFingerprint, remotePath=$remotePath, " +
            "timeoutSeconds=$timeoutSeconds, notificationEmail=$notificationEmail, " +
            "notificationName=$notificationName)"

    private fun MutableMap<String, List<String>>.validateRequiredText(
        field: String,
        displayName: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            put(field, listOf("$displayName is required"))
        } else if (value.trim().length > MAXIMUM_TEXT_LENGTH) {
            put(field, listOf("$displayName must be at most $MAXIMUM_TEXT_LENGTH characters"))
        }
    }

    private fun MutableMap<String, List<String>>.validateOptionalLength(
        field: String,
        displayName: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank() && value.trim().length > MAXIMUM_TEXT_LENGTH) {
            put(field, listOf("$displayName must be at most $MAXIMUM_TEXT_LENGTH characters"))
        }
    }

    private fun MutableMap<String, List<String>>.validateEmail(email: String?) {
        if (email.isNullOrBlank()) return

        val trimmedEmail = email.trim()
        if (trimmedEmail.length > MAXIMUM_TEXT_LENGTH) {
            put(
                "notificationEmail",
                listOf("NotificationEmail must be at most $MAXIMUM_TEXT_LENGTH characters"),
            )
        } else if (!trimmedEmail.hasValidEmailShape()) {
            put("notificationEmail", listOf("NotificationEmail must be a valid email address"))
        }
    }

    private fun String.hasValidEmailShape(): Boolean {
        val separator = indexOf('@')
        return separator > 0 &&
            separator == lastIndexOf('@') &&
            separator < lastIndex &&
            none(Char::isWhitespace)
    }

    internal companion object {
        internal val SUPPORTED_CHANNELS: Set<String> = setOf("SFTP")
        private const val MAXIMUM_TEXT_LENGTH = 255
        private const val MAXIMUM_PATH_LENGTH = 1024
        private const val MINIMUM_PORT = 1
        private const val MAXIMUM_PORT = 65535
        private const val MINIMUM_TIMEOUT_SECONDS = 1
        private const val MAXIMUM_TIMEOUT_SECONDS = 3600
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    result: OperationResult<T>
) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        OperationResult.NotFound ->
            respond(HttpStatusCode.NotFound, ApiError("Production destination not found"))
        OperationResult.Conflict ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "Production destination is in use and cannot be deleted; disable it instead"
                ),
            )
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.destinationIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid production destination id"))
    }
    return id
}

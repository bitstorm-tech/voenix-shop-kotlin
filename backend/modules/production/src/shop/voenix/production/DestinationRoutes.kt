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
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult
import shop.voenix.production.delivery.ProductionChannels
import shop.voenix.spod.SpodEnvironment
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

internal fun Application.installDestinationRoutes(destinations: ProductionDestinationOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/production/destinations") {
                installAdminRouteProtection()

                get { call.respondResult(destinations.list(), DESTINATION_RESPONSES) }

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

                        else -> call.respondFailure(result, DESTINATION_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.destinationIdOrRespond() ?: return@get
                        call.respondResult(destinations.get(id), DESTINATION_RESPONSES)
                    }

                    put {
                        val id = call.destinationIdOrRespond() ?: return@put
                        call.respondResult(
                            destinations.update(id, call.receive<ProductionDestinationInput>()),
                            DESTINATION_RESPONSES,
                        )
                    }

                    // Reads this destination's backoffice catalog into the shop's t-shirts and
                    // answers what the run did. The admin waits for it: a sync is a few seconds of
                    // partner calls, not a job to watch.
                    post("/sync-articles") {
                        val id = call.destinationIdOrRespond() ?: return@post
                        call.respondSync(destinations.syncArticles(id))
                    }

                    delete {
                        val id = call.destinationIdOrRespond() ?: return@delete
                        when (val result = destinations.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            else -> call.respondFailure(result, DESTINATION_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Admin API view of a production destination: the fields every channel has, plus exactly the detail
 * block of its channel — the same shape the request body uses.
 *
 * Neither block carries its secret: the SFTP password and the SPOD access token are write-only and
 * never appear here.
 */
@Serializable
internal data class ProductionDestination(
    val id: Long,
    val supplierId: Long,
    val channel: String,
    val label: String,
    val enabled: Boolean,
    val notificationEmail: String?,
    val notificationName: String?,
    val sftp: SftpDestinationDetails? = null,
    val spod: SpodDestinationDetails? = null,
)

/** The SFTP account of a destination, password excluded. */
@Serializable
internal data class SftpDestinationDetails(
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
)

/** The SPOD account of a destination, access token excluded. */
@Serializable
internal data class SpodDestinationDetails(
    val environment: SpodEnvironment,
    val timeoutSeconds: Int,
)

/**
 * The create/replace body of a destination.
 *
 * The channel decides which detail block the body must carry: an `SFTP` destination brings [sftp]
 * and no [spod], a `SPOD` destination the other way round. Anything else is a `channel` field
 * error, because the channel is what makes the rest of the body right or wrong — the same rule the
 * composite foreign key of the detail tables enforces in the database.
 */
@Serializable
internal data class ProductionDestinationInput(
    val supplierId: Long? = null,
    val channel: String? = null,
    val label: String? = null,
    val enabled: Boolean? = null,
    val notificationEmail: String? = null,
    val notificationName: String? = null,
    val sftp: SftpDestinationInput? = null,
    val spod: SpodDestinationInput? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (supplierId == null) {
            add("supplierId", "SupplierId is required")
        } else if (supplierId <= 0) {
            add("supplierId", "SupplierId must be positive")
        }

        if (channel.isNullOrBlank()) {
            add("channel", "Channel is required")
        } else if (channel.trim() !in SUPPORTED_CHANNELS) {
            add("channel", "Channel must be one of: ${SUPPORTED_CHANNELS.joinToString()}")
        }

        requiredText("label", "Label", label)
        validateEmail(notificationEmail)
        optionalText("notificationName", "NotificationName", notificationName)
        addChannelBlockErrors()
    }

    /**
     * Reports the detail blocks that do not match the channel and validates the one that does.
     * Nothing is reported for an unknown channel — the rule above already named it, and which
     * blocks belong to it is then unanswerable.
     */
    private fun ValidationErrorsBuilder.addChannelBlockErrors() {
        when (channel?.trim()) {
            ProductionChannels.SFTP -> {
                addBlockPresenceErrors(ProductionChannels.SFTP, sftp, spod, "spod")
                sftp?.let { block -> addAll(block.validate()) }
            }
            ProductionChannels.SPOD -> {
                addBlockPresenceErrors(ProductionChannels.SPOD, spod, sftp, "sftp")
                spod?.let { block -> addAll(block.validate()) }
            }
        }
    }

    private fun ValidationErrorsBuilder.addBlockPresenceErrors(
        channelName: String,
        ownBlock: Any?,
        foreignBlock: Any?,
        foreignBlockName: String,
    ) {
        if (ownBlock == null) {
            add("channel", "$channelName destinations require the ${channelName.lowercase()} block")
        }
        if (foreignBlock != null) {
            add("channel", "$channelName destinations must not carry the $foreignBlockName block")
        }
    }

    internal companion object {
        internal val SUPPORTED_CHANNELS: Set<String> =
            setOf(ProductionChannels.SFTP, ProductionChannels.SPOD)
    }
}

/**
 * The SFTP block of a destination body.
 *
 * The password is write-only: it is set and replaced here, is never read back, and [toString]
 * redacts it — Ktor's `RequestValidationException` message embeds the offending input's
 * `toString()`, so this is the line between a rejected body and a secret in a log file.
 */
@Serializable
internal data class SftpDestinationInput(
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val hostKeyFingerprint: String? = null,
    val remotePath: String? = null,
    val timeoutSeconds: Int? = null,
) {
    internal fun validate(): ValidationErrors = buildValidationErrors {
        requiredText("$FIELD_PREFIX.host", "Host", host)
        requiredText("$FIELD_PREFIX.username", "Username", username)
        requiredText("$FIELD_PREFIX.hostKeyFingerprint", "HostKeyFingerprint", hostKeyFingerprint)

        if (port != null && port !in MINIMUM_PORT..MAXIMUM_PORT) {
            add("$FIELD_PREFIX.port", "Port must be between $MINIMUM_PORT and $MAXIMUM_PORT")
        }

        if (password != null && password.length > MAXIMUM_TEXT_LENGTH) {
            add(
                "$FIELD_PREFIX.password",
                "Password must be at most $MAXIMUM_TEXT_LENGTH characters",
            )
        }

        if (!remotePath.isNullOrBlank() && remotePath.trim().length > MAXIMUM_PATH_LENGTH) {
            add(
                "$FIELD_PREFIX.remotePath",
                "RemotePath must be at most $MAXIMUM_PATH_LENGTH characters",
            )
        }

        requiredTimeout("$FIELD_PREFIX.timeoutSeconds", timeoutSeconds)
    }

    override fun toString(): String =
        "SftpDestinationInput(host=$host, port=$port, username=$username, " +
            "password=${redacted(password)}, hostKeyFingerprint=$hostKeyFingerprint, " +
            "remotePath=$remotePath, timeoutSeconds=$timeoutSeconds)"

    private companion object {
        const val FIELD_PREFIX = "sftp"
        const val MAXIMUM_PATH_LENGTH = 1024
        const val MINIMUM_PORT = 1
        const val MAXIMUM_PORT = 65535
    }
}

/**
 * The SPOD block of a destination body: which installation to talk to and the token to talk with.
 *
 * The access token is write-only in exactly the way the SFTP password is — never read back, never
 * in a response, redacted in [toString].
 */
@Serializable
internal data class SpodDestinationInput(
    val environment: SpodEnvironment? = null,
    val accessToken: String? = null,
    val timeoutSeconds: Int? = null,
) {
    internal fun validate(): ValidationErrors = buildValidationErrors {
        if (environment == null) {
            add("$FIELD_PREFIX.environment", "Environment is required")
        }

        if (accessToken != null && accessToken.length > MAXIMUM_TOKEN_LENGTH) {
            add(
                "$FIELD_PREFIX.accessToken",
                "AccessToken must be at most $MAXIMUM_TOKEN_LENGTH characters",
            )
        }

        requiredTimeout("$FIELD_PREFIX.timeoutSeconds", timeoutSeconds)
    }

    override fun toString(): String =
        "SpodDestinationInput(environment=$environment, accessToken=${redacted(accessToken)}, " +
            "timeoutSeconds=$timeoutSeconds)"

    internal companion object {
        internal const val FIELD_PREFIX = "spod"
        internal const val MAXIMUM_TOKEN_LENGTH = 512
    }
}

/** `null` stays `null`; anything else is a secret and never printed. */
private fun redacted(secret: String?): String = if (secret == null) "null" else "[redacted]"

private const val MAXIMUM_TEXT_LENGTH = 255
private const val MINIMUM_TIMEOUT_SECONDS = 1
private const val MAXIMUM_TIMEOUT_SECONDS = 3600

private fun ValidationErrorsBuilder.requiredText(
    field: String,
    displayName: String,
    value: String?,
) {
    if (value.isNullOrBlank()) {
        add(field, "$displayName is required")
    } else if (value.trim().length > MAXIMUM_TEXT_LENGTH) {
        add(field, "$displayName must be at most $MAXIMUM_TEXT_LENGTH characters")
    }
}

private fun ValidationErrorsBuilder.optionalText(
    field: String,
    displayName: String,
    value: String?,
) {
    if (!value.isNullOrBlank() && value.trim().length > MAXIMUM_TEXT_LENGTH) {
        add(field, "$displayName must be at most $MAXIMUM_TEXT_LENGTH characters")
    }
}

private fun ValidationErrorsBuilder.requiredTimeout(field: String, timeoutSeconds: Int?) {
    if (timeoutSeconds == null) {
        add(field, "TimeoutSeconds is required")
    } else if (timeoutSeconds !in MINIMUM_TIMEOUT_SECONDS..MAXIMUM_TIMEOUT_SECONDS) {
        add(
            field,
            "TimeoutSeconds must be between $MINIMUM_TIMEOUT_SECONDS and $MAXIMUM_TIMEOUT_SECONDS",
        )
    }
}

private fun ValidationErrorsBuilder.validateEmail(email: String?) {
    if (email.isNullOrBlank()) return

    val trimmedEmail = email.trim()
    if (trimmedEmail.length > MAXIMUM_TEXT_LENGTH) {
        add(
            "notificationEmail",
            "NotificationEmail must be at most $MAXIMUM_TEXT_LENGTH characters",
        )
    } else if (!trimmedEmail.hasValidEmailShape()) {
        add("notificationEmail", "NotificationEmail must be a valid email address")
    }
}

private fun String.hasValidEmailShape(): Boolean {
    val separator = indexOf('@')
    return separator > 0 &&
        separator == lastIndexOf('@') &&
        separator < lastIndex &&
        none(Char::isWhitespace)
}

private val DESTINATION_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Production destination not found"),
        conflict =
            ConflictHandling.Respond(
                ApiError(
                    "Production destination is in use and cannot be deleted; disable it instead"
                )
            ),
    )

/**
 * The five answers of a sync request. A run that *happened* is a `200` whatever its report says — a
 * failed run is a report about a failure, not a failed request — and the two refusals are told
 * apart by their conflict code, because the fixes differ: sync a print-on-demand destination, or
 * wait for the run that is already going.
 */
private suspend fun ApplicationCall.respondSync(result: DestinationSyncResult) {
    when (result) {
        is DestinationSyncResult.Reported -> respond(result.report)
        DestinationSyncResult.NotFound ->
            respond(HttpStatusCode.NotFound, DESTINATION_RESPONSES.notFound)
        DestinationSyncResult.NotSyncable ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "Only print-on-demand destinations have a t-shirt catalog to sync",
                    code = "CHANNEL_WITHOUT_CATALOG",
                ),
            )
        DestinationSyncResult.Busy ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "This destination is already syncing; wait for that run to finish",
                    code = "SYNC_RUNNING",
                ),
            )
        DestinationSyncResult.Failed ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.destinationIdOrRespond(): Long? =
    longPathParameterOrRespond(
        "id",
        HttpStatusCode.BadRequest,
        ApiError("Invalid production destination id"),
    )

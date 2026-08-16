package shop.voenix.production.fulfillment

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.SupplierAccounts
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.auth.installSupplierRouteProtection
import shop.voenix.auth.supplierId
import shop.voenix.http.ApiError
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.buildValidationErrors

/**
 * The HTTP surface of fulfillment: what a supplier may read about its own jobs, and what an admin
 * may read about everybody's.
 *
 * The two subtrees are disjoint route nodes with their own protection — `/api/supplier` resolves
 * the caller's supplier on every request, `/api/admin/production/jobs` requires an administrator —
 * so no request can reach one subtree with the other one's authorization. A supplier's scope is
 * never a query parameter: it comes from the protection, and the operations take it as an argument
 * they cannot be called without.
 *
 * Nothing here is cacheable. A job answer names a customer and their address, and a shared cache
 * holding it would hand one supplier another one's page.
 *
 * The one write of this surface — reporting a shipment — is a `POST` below the same protections, so
 * it carries their CSRF requirement without anything extra here. Both surfaces reach the *same*
 * service path; what an admin has and a supplier does not is the missing scope, never a second
 * implementation of the rules.
 *
 * A job id a caller may not read answers exactly like one that never existed — `404`, same body.
 * That includes an id that is not a number at all: telling a prober that the id space is numeric is
 * already more than the answer owes them.
 */
internal fun Application.installFulfillmentRoutes(
    fulfillment: FulfillmentOperations,
    accounts: SupplierAccounts,
) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            installSupplierSubtree(fulfillment, accounts)
            installAdminSubtree(fulfillment)
        }
    }
}

private fun Route.installSupplierSubtree(
    fulfillment: FulfillmentOperations,
    accounts: SupplierAccounts,
) {
    route(SUPPLIER_PATH) {
        installSupplierRouteProtection(accounts)

        get("/me") {
            call.noStore()
            call.respond(fulfillment.identity(call.supplierId()))
        }

        route("/production-jobs") {
            get {
                call.noStore()
                val status = call.statusOrRespond() ?: return@get
                call.respond(fulfillment.supplierJobs(call.supplierId(), status))
            }

            get("/{jobId}/pdf") {
                call.noStore()
                val jobId = call.jobIdOrRespond() ?: return@get
                call.respondArtifact(fulfillment.artifact(jobId, call.supplierId()))
            }

            post("/{jobId}/ship") {
                call.noStore()
                val jobId = call.jobIdOrRespond() ?: return@post
                val shipment = call.receive<ShipJobInput>().toShipment()
                call.respondShip(
                    fulfillment.shipAsSupplier(
                        jobId = jobId,
                        supplierId = call.supplierId(),
                        actorUserId = call.actorUserId(),
                        shipment = shipment,
                    )
                )
            }
        }
    }
}

private fun Route.installAdminSubtree(fulfillment: FulfillmentOperations) {
    route(ADMIN_PATH) {
        installAdminRouteProtection()

        get {
            call.noStore()
            val status = call.statusOrRespond() ?: return@get
            // Absent means "every supplier"; present but unusable is a `400` rather than a
            // silently unfiltered page — which is why the raw parameter is checked, not the
            // parsed one alone.
            val requested = call.request.queryParameters["supplierId"]
            val supplierId = requested?.toLongOrNull()?.takeIf { id -> id > 0 }
            if (requested != null && supplierId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Invalid supplier id", code = "INVALID_SUPPLIER_ID"),
                )
                return@get
            }
            call.respond(fulfillment.adminJobs(status, supplierId))
        }

        get("/{jobId}/pdf") {
            call.noStore()
            val jobId = call.jobIdOrRespond() ?: return@get
            call.respondArtifact(fulfillment.artifact(jobId, supplierScope = null))
        }

        // Ship on behalf of a supplier: the same write, the same rules, and the administrator
        // rather than the supplier recorded as the one who did it.
        post("/{jobId}/ship") {
            call.noStore()
            val jobId = call.jobIdOrRespond() ?: return@post
            val shipment = call.receive<ShipJobInput>().toShipment()
            call.respondShip(
                fulfillment.shipAsAdmin(
                    jobId = jobId,
                    actorUserId = call.actorUserId(),
                    shipment = shipment,
                )
            )
        }
    }
}

/**
 * The body of a ship request: what the supplier optionally knows about the package it just handed
 * over.
 *
 * Both fields are optional and independent. A supplier that drops a package at a counter without
 * noting anything ships with an empty body, one that only knows the number sends only the number,
 * and blank text is the same as absent — a form that submits `""` must not store an empty string
 * the mail would then print.
 *
 * There is deliberately no `trackingUrl` field. The link of the notification mail is built by the
 * shop from [ShippingCarrier]; accepting one here would let anybody with a supplier login put an
 * arbitrary link into a mail sent under the shop's name.
 */
@Serializable
internal data class ShipJobInput(
    val carrier: String? = null,
    val trackingNumber: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        val carrierName = carrier.normalized()
        if (carrierName != null && ShippingCarrier.of(carrierName) == null) {
            add(
                "carrier",
                "Carrier must be one of: " +
                    ShippingCarrier.entries.joinToString { entry -> entry.name },
            )
        }

        val number = trackingNumber.normalized()
        if (number != null && number.length > MAXIMUM_TRACKING_NUMBER_LENGTH) {
            add(
                "trackingNumber",
                "TrackingNumber must be at most $MAXIMUM_TRACKING_NUMBER_LENGTH characters",
            )
        } else if (number != null && number.any(Char::isISOControl)) {
            add("trackingNumber", "TrackingNumber must not contain control characters")
        }
    }

    /**
     * The validated body as the value the service ships with. Only call it on a body that passed
     * [validate]: an unknown carrier name is dropped here rather than stored as `null` silently.
     */
    fun toShipment(): Shipment =
        Shipment(
            carrier = ShippingCarrier.of(carrier.normalized()),
            trackingNumber = trackingNumber.normalized(),
        )
}

/** The width of `production_jobs.tracking_number`. */
private const val MAXIMUM_TRACKING_NUMBER_LENGTH = 128

/** Trimmed text, or `null` for both "absent" and "blank" — the shipping columns know one empty. */
private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private const val SUPPLIER_PATH = "/api/supplier"
private const val ADMIN_PATH = "/api/admin/production/jobs"
private const val JOB_NOT_FOUND = "Production job not found"

/** No fulfillment answer may be cached: it names a customer, an address, and a document. */
private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

/** The requested list, defaulting to the work still to be done. An unknown name is a `400`. */
private suspend fun ApplicationCall.statusOrRespond(): FulfillmentJobStatus? {
    val requested = request.queryParameters["status"] ?: return FulfillmentJobStatus.OPEN
    val status = FulfillmentJobStatus.entries.firstOrNull { it.name == requested }
    if (status == null) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError("Unknown production job status", code = "INVALID_STATUS"),
        )
    }
    return status
}

/**
 * The signed-in user this write is recorded as — the supplier login, or the administrator who
 * shipped on a supplier's behalf.
 *
 * Fails closed like [supplierId]: both subtrees are authenticated, so a call without a usable user
 * id is a wiring bug and must become a `500` rather than a shipment with an anonymous actor.
 */
private fun ApplicationCall.actorUserId(): Long =
    checkNotNull(currentUserSession()?.userId?.toLongOrNull()?.takeIf { id -> id > 0 }) {
        "No user id on this call: a protected write cannot record who performed it"
    }

/**
 * The answer of a ship request: the updated job, or the one of three refusals the guarded write
 * distinguished. None of them is a server bug — the job is not the caller's, it is already on its
 * way, or its document does not exist yet — which is why none of them is a `500`.
 */
private suspend inline fun <reified V : Any> ApplicationCall.respondShip(result: ShipResult<V>) {
    when (result) {
        is ShipResult.Shipped -> respond(result.job)
        ShipResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError(JOB_NOT_FOUND))
        ShipResult.AlreadyShipped ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "This production job has already been shipped",
                    code = "ALREADY_SHIPPED",
                ),
            )
        ShipResult.NotReady ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "This production job cannot be shipped before its document was generated",
                    code = "NOT_READY",
                ),
            )
    }
}

private suspend fun ApplicationCall.jobIdOrRespond(): Long? =
    longPathParameterOrRespond("jobId", HttpStatusCode.NotFound, ApiError(JOB_NOT_FOUND))

/**
 * Streams a verified artifact, or maps the three states an existing job's artifact can be in onto
 * their stable conflict codes. None of them is a server bug and none is the caller's fault: the
 * document is not there *yet*, not there *anymore*, or not the one that was generated.
 */
private suspend fun ApplicationCall.respondArtifact(result: FulfillmentArtifactResult) {
    when (result) {
        is FulfillmentArtifactResult.Loaded -> {
            response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${result.fileName}\"",
            )
            respondBytes(result.bytes, ContentType.Application.Pdf)
        }
        FulfillmentArtifactResult.NotFound ->
            respond(HttpStatusCode.NotFound, ApiError(JOB_NOT_FOUND))
        FulfillmentArtifactResult.NotGenerated ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "The production document for this job has not been generated yet",
                    code = "ARTIFACT_NOT_GENERATED",
                ),
            )
        FulfillmentArtifactResult.Missing ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "The production document for this job is not available",
                    code = "ARTIFACT_MISSING",
                ),
            )
        FulfillmentArtifactResult.DigestMismatch ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "The production document for this job is not available",
                    code = "ARTIFACT_DIGEST_MISMATCH",
                ),
            )
    }
}

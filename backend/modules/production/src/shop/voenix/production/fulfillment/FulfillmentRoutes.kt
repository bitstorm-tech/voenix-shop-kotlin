package shop.voenix.production.fulfillment

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.SupplierAccounts
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.auth.installSupplierRouteProtection
import shop.voenix.auth.supplierId
import shop.voenix.http.ApiError

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
 * A job id a caller may not read answers exactly like one that never existed — `404`, same body.
 * That includes an id that is not a number at all: telling a prober that the id space is numeric is
 * already more than the answer owes them.
 */
internal object FulfillmentRoutes {
    fun install(
        application: Application,
        fulfillment: FulfillmentOperations,
        accounts: SupplierAccounts,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                installSupplierSubtree(fulfillment, accounts)
                installAdminSubtree(fulfillment)
            }
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
    }
}

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

private suspend fun ApplicationCall.jobIdOrRespond(): Long? {
    val jobId = parameters["jobId"]?.toLongOrNull()
    if (jobId == null) respond(HttpStatusCode.NotFound, ApiError(JOB_NOT_FOUND))
    return jobId
}

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

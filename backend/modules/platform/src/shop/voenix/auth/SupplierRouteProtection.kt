package shop.voenix.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey

/**
 * Protects a subtree that only a supplier login may use.
 *
 * The check has two halves, and both must pass: the session must carry the exact role `SUPPLIER`,
 * and [accounts] must still resolve that user to a supplier. The second half is what makes a
 * revoked login lose access immediately — the role in the cookie stays until the next login, the
 * link in the database does not. A role without a link is therefore refused exactly like a missing
 * role, with `403`, so neither answer tells a caller which half failed.
 *
 * Mutating requests additionally need a valid CSRF token, like every other protected subtree.
 *
 * Handlers below the protection read the resolved supplier with [supplierId].
 */
public fun Route.installSupplierRouteProtection(accounts: SupplierAccounts): Unit {
    install(SupplierRouteProtection.plugin(accounts))
}

/**
 * The supplier this request acts for, resolved by [installSupplierRouteProtection].
 *
 * Fails closed: calling it on a route that is not protected by the supplier protection throws
 * instead of guessing a supplier, which turns a mis-wired route into a `500` rather than into a
 * data leak.
 */
public fun ApplicationCall.supplierId(): Long =
    checkNotNull(attributes.getOrNull(SupplierRouteProtection.SUPPLIER_ID)) {
        "No supplier id on this call: the route is not protected by installSupplierRouteProtection"
    }

private object SupplierRouteProtection {
    val SUPPLIER_ID: AttributeKey<Long> = AttributeKey("SupplierRouteProtection.supplierId")

    fun plugin(accounts: SupplierAccounts) =
        RouteProtection.failClosedPlugin(
            name = "SupplierRouteProtection",
            authorize = { call -> authorize(call, accounts) },
        )

    private suspend fun authorize(
        call: ApplicationCall,
        accounts: SupplierAccounts,
    ): Boolean {
        val principal = call.principal<UserPrincipal>()
        val supplierId =
            principal
                ?.takeIf { AuthRoles.SUPPLIER in it.roles }
                ?.userId
                ?.toLongOrNull()
                ?.let { userId -> accounts.supplierIdOf(userId) }
        return when {
            principal == null -> {
                call.respondAuth(HttpStatusCode.Unauthorized, "Authentication required")
                false
            }
            supplierId == null -> {
                call.respondAuth(HttpStatusCode.Forbidden, "Supplier access required")
                false
            }
            else -> {
                call.attributes.put(SUPPLIER_ID, supplierId)
                true
            }
        }
    }
}

package shop.voenix.auth

/**
 * Answers which supplier a logged-in user acts for.
 *
 * The account module owns the `users.supplier_id` link and implements this port; the composition
 * root hands the implementation to [installSupplierRouteProtection]. The link is resolved on every
 * request instead of being copied into the session cookie: roles in a cookie are frozen until the
 * next login, but a revoked supplier login must lose access with the very next request.
 *
 * Returning `null` means "this user is not a supplier login" — an unknown user id and a user
 * without a link are deliberately indistinguishable, and both fail closed.
 */
public fun interface SupplierAccounts {
    public suspend fun supplierIdOf(userId: Long): Long?
}

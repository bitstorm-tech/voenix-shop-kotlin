package shop.voenix.auth

/**
 * The role names the platform itself authorizes against.
 *
 * A role is a plain string in the session cookie and in the `user_roles` table. The two names here
 * are the ones a platform route protection compares against, so they live in one public place
 * instead of being repeated as literals. `CUSTOMER` is deliberately absent: no platform check asks
 * for it, and it stays where it is granted, in the account module.
 */
public object AuthRoles {
    /** Full administrative access to the `/api/admin` subtree. */
    public const val ADMIN: String = "ADMIN"

    /** A supplier login, additionally bound to one supplier by `users.supplier_id`. */
    public const val SUPPLIER: String = "SUPPLIER"
}

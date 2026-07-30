package shop.voenix.account

/**
 * Transfers what a visitor owns under a guest token to the account they just signed in with.
 *
 * The port is defined here, by the module that knows *when* a claim happens — the moment a login or
 * a registration succeeds — and not by the module that owns the claimable rows, so the account
 * module never has to depend on that module. The composition root binds the implementation once
 * both sides exist; today that is the cart's `CartGuestData` (carts and print images), and the
 * Order migration adds its rows to the same implementation without touching the account module.
 *
 * The account module calls a claim best effort: it never lets a claim failure change the HTTP
 * outcome of a login or a registration, and the next login simply claims again. An implementation
 * must therefore be idempotent and must never move rows that already belong to another account.
 */
public fun interface GuestDataClaims {
    /** Moves what [guestToken] owns to [userId]. */
    public suspend fun claim(
        userId: Long,
        guestToken: String,
    )
}

package shop.voenix.account

/**
 * Transfers what a visitor owns before signing in to the account they just signed in with.
 *
 * The port is defined here, by the module that knows *when* a claim happens — the moment a login or
 * a registration succeeds — and not by the module that owns the claimable rows, so the account
 * module never has to depend on that module. The composition root binds the implementation, and
 * today more than one module owns claimable rows: the app binds `IndependentGuestDataClaims`, which
 * calls the cart's claim (carts and print images) and the order's claim (placed orders) one after
 * the other, each independently of whether the other one worked. A further module with claimable
 * rows joins that binding without touching the account module.
 *
 * A claim has two independent handles on the same visitor, and either of them can be absent: the
 * guest token of the request, and the e-mail address of the account. The account module never
 * passes an address a visitor has not proven to own: `email` is set on login only, which requires a
 * confirmed address, and never on registration, where anybody could register a stranger's address
 * and claim their rows.
 *
 * The account module calls a claim best effort: it never lets a claim failure change the HTTP
 * outcome of a login or a registration, and the next login simply claims again. An implementation
 * must therefore be idempotent and must never move rows that already belong to another account.
 */
public fun interface GuestDataClaims {
    /**
     * Moves what [guestToken] owns — and, when [email] is given, what that confirmed address owns —
     * to [userId].
     *
     * Both handles are optional, and an implementation must treat them independently: a visitor
     * without a guest cookie can still have rows under their address, and a failing claim of one
     * kind of row must not skip the other. The account module never calls this with both handles
     * absent.
     */
    public suspend fun claim(
        userId: Long,
        guestToken: String?,
        email: String?,
    )
}

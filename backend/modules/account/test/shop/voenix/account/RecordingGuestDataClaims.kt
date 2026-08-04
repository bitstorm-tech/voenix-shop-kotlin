package shop.voenix.account

/** Records every claim the routes run, and optionally fails them or leaves rows behind. */
internal class RecordingGuestDataClaims : GuestDataClaims {
    val claims: MutableList<RecordedClaim> = mutableListOf()

    /** When set, every claim throws it — the best-effort behavior under test. */
    var failure: (() -> Exception)? = null

    /**
     * What a claim answers when it does not throw. `false` is the answer of an implementation that
     * caught a failing token-based branch itself, which is what the composition root does with a
     * cart claim that could not run — the rows stay reachable under the token, so the login must
     * not rotate it away.
     */
    var complete: Boolean = true

    override suspend fun claim(
        userId: Long,
        guestToken: String?,
        email: String?,
    ): Boolean {
        claims += RecordedClaim(userId, guestToken, email)
        failure?.let { throw it() }
        return complete
    }
}

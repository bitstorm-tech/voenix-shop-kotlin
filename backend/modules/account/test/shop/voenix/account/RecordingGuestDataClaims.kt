package shop.voenix.account

/** Records every claim the routes run, and optionally fails them all. */
internal class RecordingGuestDataClaims : GuestDataClaims {
    val claims: MutableList<RecordedClaim> = mutableListOf()

    /** When set, every claim throws it — the best-effort behavior under test. */
    var failure: (() -> Exception)? = null

    override suspend fun claim(
        userId: Long,
        guestToken: String?,
        email: String?,
    ) {
        claims += RecordedClaim(userId, guestToken, email)
        failure?.let { throw it() }
    }
}

package shop.voenix.account

/** Records every claim the routes run, and optionally fails them all. */
internal class RecordingGuestDataClaims : GuestDataClaims {
    val claims: MutableList<Pair<Long, String>> = mutableListOf()

    /** When set, every claim throws it — the best-effort behavior under test. */
    var failure: (() -> Exception)? = null

    override suspend fun claim(
        userId: Long,
        guestToken: String,
    ) {
        claims += userId to guestToken
        failure?.let { throw it() }
    }
}

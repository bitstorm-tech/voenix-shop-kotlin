package shop.voenix.account

/** One call the routes made to the claim port, with both handles exactly as they arrived. */
internal data class RecordedClaim(
    val userId: Long,
    val guestToken: String?,
    val email: String?,
)

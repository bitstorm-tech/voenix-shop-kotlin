package shop.voenix.cart

/**
 * Who a cart operation is for: the user id when the request is signed in, plus the guest session
 * token the browser carries.
 *
 * The two are not symmetrical, and which of them identifies the cart depends on the request: a
 * signed-in request finds and creates its cart by [userId], an anonymous one by [guestToken]. A
 * cart therefore never carries both — the claim on login moves it from the one identity to the
 * other — and the database has a unique rule for each half (issue #77, which supersedes deviation
 * 14 of the cart migration).
 *
 * [guestToken] still matters for a signed-in caller, because two things next to the cart keep their
 * own ownership rule: a print image belongs to its token *or* its user, and so does an ordered line
 * a reorder starts from.
 *
 * At least one of the two is always set. A request with neither is not an owner at all, and the
 * route answers it with the empty cart instead of constructing one.
 */
internal data class CartOwner(
    val guestToken: String?,
    val userId: Long?,
)

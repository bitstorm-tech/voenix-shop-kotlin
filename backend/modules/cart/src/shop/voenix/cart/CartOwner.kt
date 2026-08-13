package shop.voenix.cart

/**
 * Who a cart operation is for: the user id when the request is signed in, plus the guest session
 * token the browser carries.
 *
 * The two are not symmetrical, and which of them identifies the cart depends on the request: a
 * signed-in request finds and creates its cart by [userId], an anonymous one by [guestToken]. A
 * cart therefore never carries both, and it keeps the identity it was created with for life:
 * nothing moves a cart from the one identity to the other, so a login does not touch a single cart
 * row. `ck_carts_single_owner` and a unique index per half are the database's version of that rule
 * (issue #77, which supersedes deviation 14 of the cart migration; the claim that once moved carts
 * is gone with issue #110).
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

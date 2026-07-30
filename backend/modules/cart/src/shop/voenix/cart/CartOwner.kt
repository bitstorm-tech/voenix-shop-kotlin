package shop.voenix.cart

/**
 * Who a cart operation is for: the guest session token the browser carries, plus the user id when
 * the same request is also signed in.
 *
 * The token is the identity of a cart, always — even for a signed-in customer. The user id is only
 * ever *adopted* onto a cart that has none yet, which is what turns the anonymous cart of a visitor
 * into the cart of the account they just logged into. Looking a cart up by user id instead would
 * need a second uniqueness rule and would silently merge two devices' carts; the migration record
 * (deviation 14) decided against both.
 */
internal data class CartOwner(
    val guestToken: String,
    val userId: Long?,
)

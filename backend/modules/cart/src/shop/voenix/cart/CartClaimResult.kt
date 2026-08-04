package shop.voenix.cart

/**
 * What one attempt at the login claim did with the visitor's cart.
 *
 * The type exists for the one outcome the caller has to act on: [Conflict] says that the database
 * refused the claim because the customer gained an active cart while this one was running — which
 * is not a failure but the signal to run the claim once more, as a merge into the cart that won.
 * [Claimed] carries nothing, because everything a claim decides — the moved lines, the retired
 * cart, the promotion capacity it gave back — is already committed with it.
 */
internal sealed interface CartClaimResult {
    /**
     * The claim ran: the visitor had no cart, or their cart became the customer's, or its lines
     * were merged into the cart the customer already had, or it was retired as it stood because an
     * order of it is still waiting to be paid.
     */
    data object Claimed : CartClaimResult

    /**
     * The unique index over active carts refused to give this user a second one. A concurrent login
     * or mutation of the same customer created their active cart between this attempt's lock and
     * its write, so the answer is to try again — the retry then finds that cart and merges into it.
     */
    data object Conflict : CartClaimResult
}

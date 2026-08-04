package shop.voenix.cart

/**
 * What one attempt at the login claim did with the visitor's cart.
 *
 * The type exists because the claim has an outcome the caller has to act on twice over. [Claimed]
 * carries the guest cart a merge retired, so the module can hand back the promotion capacity that
 * cart was still holding, and [Conflict] says that the database refused the claim because the
 * customer gained an active cart while this one was running — which is not a failure but the signal
 * to run the claim once more, as a merge into the cart that won.
 */
internal sealed interface CartClaimResult {
    /**
     * The claim ran. [retiredCartId] is the guest cart whose lines were merged into the customer's
     * existing cart, or `null` when nothing was retired — because the visitor had no cart at all,
     * or because the customer had none and the guest cart simply became theirs.
     */
    data class Claimed(val retiredCartId: Long?) : CartClaimResult

    /**
     * The unique index over active carts refused to give this user a second one. A concurrent login
     * or mutation of the same customer created their active cart between this attempt's lock and
     * its write, so the answer is to try again — the retry then finds that cart and merges into it.
     */
    data object Conflict : CartClaimResult
}

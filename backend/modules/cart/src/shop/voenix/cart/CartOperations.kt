package shop.voenix.cart

import shop.voenix.image.UploadedImage
import shop.voenix.operation.OperationResult

/**
 * Everything a customer can do with their cart, expressed once so that the routes stay a mapping
 * from HTTP to these seven calls and back.
 *
 * Every operation takes the [CartOwner] rather than reading a cookie itself: who the caller is, is
 * an HTTP question, and answering it in the route is what lets a test drive the whole cart without
 * a browser.
 *
 * All operations but two answer with the complete recalculated [CartView] — the upload answers with
 * the id it stored, and applying a promotion answers with [CartPromotionResult], because a rejected
 * coupon code is neither a validation error of the request nor a conflict.
 */
internal interface CartOperations {
    /** The active cart of [owner], or [CartView.EMPTY] when they have none yet. */
    suspend fun cart(owner: CartOwner): OperationResult<CartView>

    /**
     * Stores [upload] as a print image owned by [owner] and returns the id an add references it by.
     * A row that cannot be written takes the already stored file with it, so a failed upload leaves
     * nothing behind.
     */
    suspend fun uploadPrintImage(
        owner: CartOwner,
        upload: UploadedImage,
    ): OperationResult<PrintImageId>

    /**
     * Adds one line, creating the cart when [owner] has none. An identical line — same article,
     * variant, price snapshot, image, prompt, and prompt price — is merged into the existing one
     * and capped at 99.
     */
    suspend fun addItem(
        owner: CartOwner,
        input: AddCartItemInput,
    ): OperationResult<CartView>

    suspend fun updateQuantity(
        owner: CartOwner,
        itemId: Long,
        input: CartQuantityInput,
    ): OperationResult<CartView>

    suspend fun removeItem(
        owner: CartOwner,
        itemId: Long,
    ): OperationResult<CartView>

    /**
     * Validates a coupon code and stores it on the cart. Only the promotion id is stored: what the
     * discount is worth is recalculated on every read, and whether it may still be used is decided
     * again at checkout.
     */
    suspend fun applyPromotion(
        owner: CartOwner,
        input: PromotionCodeInput,
    ): CartPromotionResult

    suspend fun removePromotion(owner: CartOwner): OperationResult<CartView>
}

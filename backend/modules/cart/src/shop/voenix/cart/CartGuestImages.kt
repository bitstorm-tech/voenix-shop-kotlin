package shop.voenix.cart

import shop.voenix.image.GuestImageResolver

/**
 * The cart's answer to the image module's only question about a print image: does this caller own
 * it, and under which file name is it stored?
 *
 * The class is public because the composition root hands it to `installGuestImageRoute`, but it
 * carries nothing else outward. It never tells anybody whether an image *exists* — a foreign image
 * and an unknown id both answer `null`, so the route turns both into `404` and an id cannot be
 * probed.
 */
public class CartGuestImages internal constructor(private val repository: CartRepository) :
    GuestImageResolver {
    override suspend fun resolve(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String? = repository.findPrintImage(imageId, guestToken, userId)?.filename
}

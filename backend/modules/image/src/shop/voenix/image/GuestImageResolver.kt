package shop.voenix.image

/**
 * Answers the only question the guest delivery route cannot answer itself: does this caller own the
 * private image with this id, and under which stored file name?
 *
 * The port is defined here, not by the module that owns the ownership records, so that the image
 * module never has to depend on that module. The composition root binds the implementation after
 * both sides exist.
 *
 * The interface is deliberately path-free and answer-free: it takes an id and whatever identity the
 * request carried, and returns a stored file name or `null`. It must not distinguish "no such
 * image" from "someone else's image" — the route turns both into `404`, so an id cannot be probed
 * for existence.
 */
public fun interface GuestImageResolver {
    /**
     * Returns the stored file name of [imageId] when it belongs to the caller identified by
     * [guestToken] or [userId], and `null` otherwise. Both identities are optional: an anonymous
     * request without a guest cookie owns nothing.
     */
    public suspend fun resolve(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String?
}

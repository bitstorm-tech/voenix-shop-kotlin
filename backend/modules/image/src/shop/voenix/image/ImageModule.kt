package shop.voenix.image

import io.ktor.server.application.Application
import shop.voenix.auth.GuestTokens

/**
 * The runtime handle of the installed image module.
 *
 * It is public because the composition root has to hand the two storage capabilities to the modules
 * that store images, and hand the module back later to install the guest delivery route. Everything
 * behind those capabilities — the service, the roots, the cache — stays internal.
 */
public class ImageModule
internal constructor(
    internal val operations: ImageOperations,
    public val publicStorage: PublicImageStorage,
    public val privateStorage: PrivateImageStorage,
) {
    internal fun install(application: Application): Unit =
        ImageRoutes.install(application, operations)
}

internal fun createImageModule(settings: ImageSettings): ImageModule {
    val service = ImageService(settings)
    return ImageModule(operations = service, publicStorage = service, privateStorage = service)
}

internal fun Application.installImageModule(images: ImageOperations) =
    ImageRoutes.install(this, images)

internal fun Application.installGuestImageRoute(
    images: ImageOperations,
    guestTokens: GuestTokens,
    resolver: GuestImageResolver,
): Unit = ImageRoutes.installGuestRoute(this, images, guestTokens, resolver)

public fun Application.installImageModule(settings: ImageSettings): ImageModule =
    createImageModule(settings).also { it.install(this) }

/**
 * Installs `GET /api/images/guest/{size}/{id}` against [resolver].
 *
 * A separate composition step, because the resolver belongs to a module installed after image. Call
 * it once, after [installImageModule].
 */
public fun Application.installGuestImageRoute(
    images: ImageModule,
    guestTokens: GuestTokens,
    resolver: GuestImageResolver,
): Unit = ImageRoutes.installGuestRoute(this, images.operations, guestTokens, resolver)

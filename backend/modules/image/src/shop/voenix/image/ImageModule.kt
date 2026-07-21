package shop.voenix.image

import io.ktor.server.application.Application

internal class ImageModule
internal constructor(
    internal val operations: ImageOperations,
    val publicStorage: PublicImageStorage,
) {
    internal fun install(application: Application): Unit =
        ImageRoutes.install(application, operations)
}

internal fun createImageModule(settings: ImageSettings): ImageModule {
    val service = ImageService(settings)
    return ImageModule(operations = service, publicStorage = service)
}

internal fun Application.installImageModule(images: ImageOperations): Unit =
    ImageRoutes.install(this, images)

public fun Application.installImageModule(settings: ImageSettings): PublicImageStorage {
    val module = createImageModule(settings)
    module.install(this)
    return module.publicStorage
}

package shop.voenix.image

import shop.voenix.operation.OperationResult

public interface PublicImageStorage {
    public suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage>

    public suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean>

    public suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit>
}

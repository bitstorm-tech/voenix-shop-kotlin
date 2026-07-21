package shop.voenix.image

import shop.voenix.operation.OperationResult

internal interface ImageOperations {
    suspend fun get(
        visibility: ImageVisibility,
        requestedSize: String,
        filename: String,
    ): OperationResult<ImageResource>
}

package shop.voenix.image

import shop.voenix.operation.OperationResult

/**
 * Stores print images that only their owner may retrieve.
 *
 * Unlike [PublicImageStorage] this capability names no folder. Private originals live in one
 * image-owned directory below the private root, and a caller never learns where that is: it hands
 * over bytes and receives a file name, and it hands back a file name to check or delete one. The
 * delivery side of the same file is the guest route, which resolves the very same name through
 * [GuestImageResolver].
 *
 * Every accepted upload is normalized to WebP, so a stored name is always a lowercase UUID with
 * dashes plus `.webp`. JPEG, PNG, and WebP are the accepted inputs; GIF and everything else is
 * rejected before anything is written.
 */
public interface PrivateImageStorage {
    public suspend fun store(upload: ImageUpload): OperationResult<StoredPrivateImage>

    public suspend fun exists(filename: String): OperationResult<Boolean>

    public suspend fun delete(filename: String): OperationResult<Unit>
}

/**
 * The one directory below the private root that holds print-image originals.
 *
 * It is internal on purpose: storage callers pass file names only, and the guest route is the sole
 * place that has to combine the folder with a resolved name to read the file back.
 */
internal const val PRINT_IMAGE_FOLDER: String = "print-images"

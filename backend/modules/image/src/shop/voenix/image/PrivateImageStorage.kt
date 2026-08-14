package shop.voenix.image

import java.nio.file.Path
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

    /**
     * Resolves stored file names to the readable originals behind them, in one call.
     *
     * This is the one place a caller receives a [Path] instead of a name, and it exists because a
     * consumer that has to *read* the bytes — production renders them into a PDF — would otherwise
     * have to know the private root and build the path itself. It does not: it hands over the names
     * it stored and receives ready paths, so the root, the image-owned folder, and the containment
     * check all stay here.
     *
     * Set in, map out, like `shop.voenix.article.ArticleCatalog.find`: a name the storage cannot
     * answer for is **absent** from the map rather than mapped to `null`. Deleted file, name that
     * never existed, name that is not a plain file name at all — the caller handles one case, and a
     * missing original is its own decision to make. An empty set is answered without touching the
     * file system.
     *
     * The returned paths are a snapshot: the file may be deleted right after the call, so a reader
     * still has to survive an unreadable path.
     */
    public suspend fun originalPaths(filenames: Set<String>): OperationResult<Map<String, Path>>
}

public data class StoredPrivateImage(public val filename: String)

/**
 * The one directory below the private root that holds print-image originals.
 *
 * It is internal on purpose: storage callers pass file names only, and the guest route is the sole
 * place that has to combine the folder with a resolved name to read the file back.
 */
internal const val PRINT_IMAGE_FOLDER: String = "print-images"

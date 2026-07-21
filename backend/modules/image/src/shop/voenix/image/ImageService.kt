package shop.voenix.image

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class ImageService(
    private val settings: ImageSettings,
    private val processingSlots: Semaphore = defaultProcessingSlots,
) : ImageOperations, PublicImageStorage {
    private val codec = ImageCodec()
    private val files = ImageFiles(settings)
    private val keyLocks = ConcurrentHashMap<String, KeyLock>()

    internal val activeKeyLockCount: Int
        get() = keyLocks.size

    @Suppress("TooGenericExceptionCaught")
    override suspend fun get(
        visibility: ImageVisibility,
        requestedSize: String,
        filename: String,
    ): OperationResult<ImageResource> {
        val size =
            ImageSize.parse(requestedSize)
                ?: return invalid("size", "Size must be width or widthxheight from 1 through 4096")
        val relative =
            parseRelativeImagePath(filename) ?: return invalid("filename", "Invalid image filename")
        val format =
            ImageCodec.Format.fromFilename(filename)
                ?: return invalid("filename", "Unsupported image format")

        return withContext(Dispatchers.IO) {
            try {
                when (val original = files.resolveExisting(originalRoot(visibility), relative)) {
                    ImageFiles.ResolvedPath.Invalid -> invalid("filename", "Invalid image filename")
                    ImageFiles.ResolvedPath.Missing -> OperationResult.NotFound
                    is ImageFiles.ResolvedPath.Found ->
                        getOrCreateDerived(visibility, size, relative, original.path, format)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logger.error("Image processing failed for {}", filename, failure)
                OperationResult.UnexpectedFailure
            }
        }
    }

    override suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage> {
        val declaredFormat =
            ImageCodec.Format.fromContentType(upload.contentType)
                ?: return invalid("image", "Only JPEG, PNG, and WebP uploads are supported")
        if (upload.byteCount == 0) return invalid("image", "Image must not be empty")
        val uploadBytes = upload.bytes ?: return invalid("image", "Image must not exceed 10 MiB")

        return storageOperation("store public image") {
            val directory =
                files.ensureDirectoryInside(
                    settings.publicRoot,
                    settings.publicRoot.resolve(folder.path),
                ) ?: return@storageOperation invalid("folder", "Invalid public image folder")
            val filename = "${UUID.randomUUID()}.webp"
            val target = directory.resolve(filename)
            val temporary = directory.resolve(".$filename.${UUID.randomUUID()}.tmp")
            try {
                processingSlots.withPermit {
                    val decoded =
                        try {
                            codec.decode(uploadBytes)
                        } catch (invalid: IllegalArgumentException) {
                            return@storageOperation invalid(
                                "image",
                                invalid.message ?: "Invalid image data",
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            return@storageOperation invalid("image", "Invalid image data")
                        }
                    if (decoded.format != declaredFormat) {
                        return@storageOperation invalid(
                            "image",
                            "Declared and decoded image formats differ",
                        )
                    }
                    codec.write(decoded.image, ImageCodec.Format.WEBP, temporary)
                }
                files.moveAtomically(temporary, target)
                OperationResult.Success(StoredPublicImage(filename))
            } finally {
                files.deleteTemporaryBestEffort(temporary)
            }
        }
    }

    override suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean> {
        val relative =
            simpleFilename(filename) ?: return invalid("filename", "Invalid image filename")
        return storageOperation("check public image") {
            val combined = folder.path.resolve(relative)
            when (files.resolveExisting(settings.publicRoot, combined)) {
                ImageFiles.ResolvedPath.Invalid -> invalid("filename", "Invalid image filename")
                ImageFiles.ResolvedPath.Missing -> OperationResult.Success(false)
                is ImageFiles.ResolvedPath.Found -> OperationResult.Success(true)
            }
        }
    }

    override suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit> {
        val relative =
            simpleFilename(filename) ?: return invalid("filename", "Invalid image filename")
        return storageOperation("delete public image") {
            val combined = folder.path.resolve(relative)
            withKeyLock(originalLockKey(ImageVisibility.PUBLIC, combined)) {
                when (
                    val original =
                        files.resolveOptional(
                            settings.publicRoot,
                            combined,
                            requireRegularFile = true,
                        )
                ) {
                    ImageFiles.ResolvedPath.Invalid ->
                        return@withKeyLock invalid("filename", "Invalid image filename")
                    ImageFiles.ResolvedPath.Missing -> Unit
                    is ImageFiles.ResolvedPath.Found -> Files.deleteIfExists(original.path)
                }
                files.deletePublicDerivations(combined)
                OperationResult.Success(Unit)
            }
        }
    }

    /**
     * Derivation coordinates three layers, always acquired in this order:
     *
     * 1. Cache-key lock (`cachePath`): at most one coroutine derives a given
     *    visibility/size/filename combination; late arrivals re-check freshness after acquiring it.
     * 2. Processing slot: bounds concurrent decode/encode work (heap and CPU).
     * 3. Original-key lock: publishing a derived file and deleting the original are mutually
     *    exclusive, so [delete] cannot lose against an in-flight derivation.
     *
     * The original may be replaced while a coroutine waits for a slot, so a
     * [ImageFiles.SourceVersion] snapshot is taken up front and compared twice: after acquiring the
     * slot (abandon stale work before decoding) and under the original lock immediately before
     * publishing (never publish stale pixels). A detected change makes [deriveOnce] return null and
     * the caller retries up to [MAX_SOURCE_STABILITY_ATTEMPTS] times.
     *
     * Lock order is strictly cache lock -> original lock; [delete] takes only the original lock, so
     * no cycle is possible.
     */
    private suspend fun getOrCreateDerived(
        visibility: ImageVisibility,
        size: ImageSize,
        relative: Path,
        original: Path,
        format: ImageCodec.Format,
    ): OperationResult<ImageResource> {
        val cachePath =
            settings.cacheRoot
                .resolve(visibility.cacheDirectory)
                .resolve(size.cacheKey)
                .resolve(relative)
                .normalize()
        if (!cachePath.startsWith(settings.cacheRoot)) {
            return invalid("filename", "Invalid image filename")
        }
        val fresh = files.freshResource(cachePath, original, format.contentType)
        return if (fresh != null) {
            OperationResult.Success(fresh)
        } else {
            withKeyLock(cachePath.toString()) cacheLock@{
                files.freshResource(cachePath, original, format.contentType)?.let {
                    return@cacheLock OperationResult.Success(it)
                }
                val parent =
                    files.ensureDirectoryInside(settings.cacheRoot, checkNotNull(cachePath.parent))
                        ?: return@cacheLock invalid("filename", "Invalid image filename")
                val request =
                    DerivationRequest(visibility, size, relative, cachePath, parent, format)
                repeat(MAX_SOURCE_STABILITY_ATTEMPTS) {
                    deriveOnce(request)?.let { result ->
                        return@cacheLock result
                    }
                }
                logger.warn("Image source changed repeatedly while deriving {}", relative)
                OperationResult.UnexpectedFailure
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun deriveOnce(request: DerivationRequest): OperationResult<ImageResource>? {
        val visibility = request.visibility
        val relative = request.relative
        val currentOriginal =
            when (val resolved = files.resolveExisting(originalRoot(visibility), relative)) {
                ImageFiles.ResolvedPath.Invalid ->
                    return invalid("filename", "Invalid image filename")
                ImageFiles.ResolvedPath.Missing -> return OperationResult.NotFound
                is ImageFiles.ResolvedPath.Found -> resolved.path
            }
        val sourceSnapshot = files.sourceVersion(currentOriginal)
        val temporary =
            request.parent.resolve(".${request.cachePath.fileName}.${UUID.randomUUID()}.tmp")
        try {
            val processed = processingSlots.withPermit {
                when (val latest = files.resolveExisting(originalRoot(visibility), relative)) {
                    ImageFiles.ResolvedPath.Invalid ->
                        return invalid("filename", "Invalid image filename")
                    ImageFiles.ResolvedPath.Missing -> return OperationResult.NotFound
                    is ImageFiles.ResolvedPath.Found ->
                        if (
                            latest.path != currentOriginal ||
                                files.sourceVersion(latest.path) != sourceSnapshot
                        ) {
                            return@withPermit false
                        }
                }
                val decoded = codec.decode(currentOriginal)
                codec.write(request.size.resize(decoded.image), request.format, temporary)
                true
            }
            if (!processed) return null
            return withKeyLock(originalLockKey(visibility, relative)) publishLock@{
                when (val latest = files.resolveExisting(originalRoot(visibility), relative)) {
                    ImageFiles.ResolvedPath.Invalid -> invalid("filename", "Invalid image filename")
                    ImageFiles.ResolvedPath.Missing -> OperationResult.NotFound
                    is ImageFiles.ResolvedPath.Found -> {
                        val unchanged =
                            latest.path == currentOriginal &&
                                files.sourceVersion(latest.path) == sourceSnapshot
                        if (!unchanged) return@publishLock null
                        files.moveAtomically(temporary, request.cachePath)
                        OperationResult.Success(
                            files.resource(request.cachePath, request.format.contentType)
                        )
                    }
                }
            }
        } finally {
            files.deleteTemporaryBestEffort(temporary)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storageOperation(
        description: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        withContext(Dispatchers.IO) {
            try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logger.error("Failed to {}", description, failure)
                OperationResult.UnexpectedFailure
            }
        }

    private suspend fun <T> withKeyLock(
        key: String,
        operation: suspend () -> T,
    ): T {
        val holder =
            checkNotNull(
                keyLocks.compute(key) { _, current ->
                    (current ?: KeyLock()).also { it.users.incrementAndGet() }
                }
            )
        try {
            return holder.mutex.withLock { operation() }
        } finally {
            keyLocks.compute(key) { _, current ->
                if (current !== holder) {
                    current
                } else if (holder.users.decrementAndGet() == 0) {
                    null
                } else {
                    holder
                }
            }
        }
    }

    private fun originalRoot(visibility: ImageVisibility): Path =
        when (visibility) {
            ImageVisibility.PUBLIC -> settings.publicRoot
            ImageVisibility.PRIVATE -> settings.privateRoot
        }

    private fun originalLockKey(
        visibility: ImageVisibility,
        relative: Path,
    ): String = "original:${visibility.cacheDirectory}:$relative"

    private fun parseRelativeImagePath(value: String): Path? {
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\')) {
            return null
        }
        if ('\\' in value) {
            return null
        }
        if (value.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return try {
            Path.of(value).takeIf { !it.isAbsolute && it.normalize() == it }
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun simpleFilename(value: String): Path? {
        val relative = parseRelativeImagePath(value) ?: return null
        return relative.takeIf { it.nameCount == 1 }
    }

    private fun <T> invalid(
        field: String,
        message: String,
    ): OperationResult<T> = OperationResult.Invalid(mapOf(field to listOf(message)))

    private class KeyLock {
        val mutex = Mutex()
        val users = AtomicInteger()
    }

    private data class DerivationRequest(
        val visibility: ImageVisibility,
        val size: ImageSize,
        val relative: Path,
        val cachePath: Path,
        val parent: Path,
        val format: ImageCodec.Format,
    )

    private companion object {
        private const val MAX_SOURCE_STABILITY_ATTEMPTS = 3

        private val logger = LoggerFactory.getLogger(ImageService::class.java)

        // Each decoded 40-megapixel image holds roughly 160 MB of heap, so slots
        // scale with cores but stay bounded to protect memory on large machines.
        private val defaultProcessingSlots =
            Semaphore((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8))
    }
}

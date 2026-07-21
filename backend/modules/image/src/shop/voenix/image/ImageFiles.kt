package shop.voenix.image

import io.ktor.http.ContentType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import org.slf4j.LoggerFactory

internal class ImageFiles(private val settings: ImageSettings) {
    fun freshResource(
        cachePath: Path,
        original: Path,
        contentType: ContentType,
    ): ImageResource? {
        val realCache =
            if (Files.isRegularFile(cachePath, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { cachePath.toRealPath() }.getOrNull()
            } else {
                null
            }
        val originalModified = runCatching {
            Files.getLastModifiedTime(original).toMillis()
        }
            .getOrNull()
        val cacheIsSafe = realCache != null && realCache.startsWith(settings.cacheRoot)
        val cacheIsFresh =
            cacheIsSafe &&
                originalModified != null &&
                Files.getLastModifiedTime(checkNotNull(realCache)).toMillis() >= originalModified
        return if (cacheIsFresh) {
            resource(checkNotNull(realCache), contentType)
        } else {
            null
        }
    }

    fun resource(
        path: Path,
        contentType: ContentType,
    ): ImageResource =
        ImageResource(
            path = path,
            contentType = contentType,
            length = Files.size(path),
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
        )

    fun sourceVersion(path: Path): SourceVersion {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return SourceVersion(
            fileKey = attributes.fileKey(),
            size = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    fun resolveExisting(
        root: Path,
        relative: Path,
    ): ResolvedPath = resolveOptional(root, relative, requireRegularFile = true)

    fun resolveOptional(
        root: Path,
        relative: Path,
        requireRegularFile: Boolean = false,
    ): ResolvedPath {
        val target = root.resolve(relative).normalize()
        return when {
            relative.isAbsolute || relative.normalize() != relative -> ResolvedPath.Invalid
            !target.startsWith(root) -> ResolvedPath.Invalid
            !Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> ResolvedPath.Missing
            else -> resolveRealPath(root, target, requireRegularFile)
        }
    }

    fun ensureDirectoryInside(
        root: Path,
        directory: Path,
    ): Path? {
        val normalized = directory.normalize()
        var ancestor: Path? = normalized
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        val realAncestor = ancestor?.let { runCatching { it.toRealPath() }.getOrNull() }
        return if (
            normalized.startsWith(root) && realAncestor != null && realAncestor.startsWith(root)
        ) {
            Files.createDirectories(normalized)
            normalized.toRealPath().takeIf { it.startsWith(root) && Files.isDirectory(it) }
        } else {
            null
        }
    }

    fun deletePublicDerivations(relative: Path) {
        val visibilityRoot = settings.cacheRoot.resolve(ImageVisibility.PUBLIC.cacheDirectory)
        if (!Files.isDirectory(visibilityRoot, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(visibilityRoot).use { sizes ->
            sizes
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { sizeRoot -> deletePublicDerivation(sizeRoot, visibilityRoot, relative) }
        }
    }

    fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun deleteTemporaryBestEffort(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { failure ->
                logger.warn("Could not delete temporary image file {}", path, failure)
            }
    }

    private fun resolveRealPath(
        root: Path,
        target: Path,
        requireRegularFile: Boolean,
    ): ResolvedPath {
        val real = runCatching { target.toRealPath() }.getOrNull()
        return when {
            real == null || !real.startsWith(root) -> ResolvedPath.Invalid
            requireRegularFile && !Files.isRegularFile(real) -> ResolvedPath.Missing
            else -> ResolvedPath.Found(real)
        }
    }

    private fun deletePublicDerivation(
        sizeRoot: Path,
        visibilityRoot: Path,
        relative: Path,
    ) {
        val realSizeRoot = runCatching { sizeRoot.toRealPath() }.getOrNull()
        if (realSizeRoot == null || !realSizeRoot.startsWith(settings.cacheRoot)) return
        val cached = resolveOptional(realSizeRoot, relative, requireRegularFile = true)
        if (cached is ResolvedPath.Found) {
            Files.deleteIfExists(cached.path)
            deleteEmptyParents(cached.path.parent, visibilityRoot)
        }
    }

    private fun deleteEmptyParents(
        start: Path?,
        boundary: Path,
    ) {
        var current = start
        while (current != null && current != boundary && current.startsWith(boundary)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return
            val empty = Files.list(current).use { entries -> !entries.findAny().isPresent }
            if (!empty) return
            Files.deleteIfExists(current)
            current = current.parent
        }
    }

    data class SourceVersion(
        val fileKey: Any?,
        val size: Long,
        val lastModifiedMillis: Long,
    )

    sealed interface ResolvedPath {
        data object Invalid : ResolvedPath

        data object Missing : ResolvedPath

        data class Found(val path: Path) : ResolvedPath
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ImageFiles::class.java)
    }
}

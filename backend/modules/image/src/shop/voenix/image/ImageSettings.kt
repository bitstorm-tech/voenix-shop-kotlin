package shop.voenix.image

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path

public class ImageSettings
internal constructor(
    internal val publicRoot: Path,
    internal val privateRoot: Path,
    internal val cacheRoot: Path,
) {
    public companion object {
        public fun from(
            config: ApplicationConfig,
            workingDirectory: Path = Path.of("").toAbsolutePath(),
        ): ImageSettings {
            fun required(name: String): String =
                config.propertyOrNull("Image.$name")?.getString()?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: Image.$name")

            return create(
                publicRoot = Path.of(required("PublicRoot")),
                privateRoot = Path.of(required("PrivateRoot")),
                cacheRoot = Path.of(required("CacheRoot")),
                workingDirectory = workingDirectory,
            )
        }

        internal fun create(
            publicRoot: Path,
            privateRoot: Path,
            cacheRoot: Path,
            workingDirectory: Path = Path.of("").toAbsolutePath(),
        ): ImageSettings {
            val base = workingDirectory.toAbsolutePath().normalize()
            val roots =
                listOf(publicRoot, privateRoot, cacheRoot).map { configured ->
                    val absolute =
                        if (configured.isAbsolute) {
                            configured.normalize()
                        } else {
                            base.resolve(configured).normalize()
                        }
                    if (Files.exists(absolute) && !Files.isDirectory(absolute)) {
                        error("Image root is not a directory: $absolute")
                    }
                    Files.createDirectories(absolute)
                    val real = absolute.toRealPath()
                    check(Files.isWritable(real)) { "Image root is not writable: $real" }
                    real
                }

            roots.forEachIndexed { index, root ->
                roots.drop(index + 1).forEach { other ->
                    require(!root.startsWith(other) && !other.startsWith(root)) {
                        "Image roots must not overlap: $root and $other"
                    }
                }
            }

            return ImageSettings(
                publicRoot = roots[0],
                privateRoot = roots[1],
                cacheRoot = roots[2],
            )
        }
    }
}

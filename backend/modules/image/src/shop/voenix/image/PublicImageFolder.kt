package shop.voenix.image

import java.nio.file.Path

public class PublicImageFolder private constructor(internal val path: Path) {
    /**
     * The relative folder as it was named. The [path] itself is internal to this module, so this is
     * how a log line — or a test of an owning module — can say *which* folder a call went to.
     */
    override fun toString(): String = path.toString()

    public companion object {
        public fun of(value: String): PublicImageFolder {
            require(value.isNotBlank()) { "Public image folder must not be blank" }
            require(!value.startsWith('/') && !value.startsWith('\\')) {
                "Public image folder must be relative"
            }
            require('\\' !in value) { "Public image folder must use forward slashes" }
            val segments = value.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                "Public image folder contains an unsafe segment"
            }
            val path = Path.of(value)
            require(!path.isAbsolute && path.normalize() == path) {
                "Public image folder must be a normalized relative path"
            }
            return PublicImageFolder(path)
        }
    }
}

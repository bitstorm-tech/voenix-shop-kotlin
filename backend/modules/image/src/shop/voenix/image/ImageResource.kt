package shop.voenix.image

import io.ktor.http.ContentType
import java.nio.file.Path

internal data class ImageResource(
    val path: Path,
    val contentType: ContentType,
    val length: Long,
    val lastModifiedMillis: Long,
)

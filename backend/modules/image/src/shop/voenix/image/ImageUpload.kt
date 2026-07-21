package shop.voenix.image

public class ImageUpload(
    bytes: ByteArray,
    public val contentType: String,
) {
    internal val byteCount: Int = bytes.size
    internal val bytes: ByteArray? = bytes.takeIf { it.size <= MAX_BYTES }?.copyOf()

    public companion object {
        public const val MAX_BYTES: Int = 10 * 1024 * 1024
    }
}

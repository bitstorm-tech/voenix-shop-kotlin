package shop.voenix.production.pdf

import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal class ProductionArtifactStoreTest {
    private val root = newTempDirectory().toRealPath()
    private val store = ProductionArtifactStore(root)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `write persists the bytes under the job-scoped path without leftover temp files`() {
        val bytes = "first artifact".toByteArray()

        val target = store.write(jobId = 7, fileName = "ORD-10.pdf", bytes = bytes)

        assertEquals(root.resolve("7").resolve("ORD-10.pdf"), target)
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertEquals(listOf(target), root.resolve("7").listDirectoryEntries())
    }

    @Test
    fun `write replaces an existing file so a repeated attempt heals`() {
        store.write(jobId = 7, fileName = "ORD-10.pdf", bytes = "half a pd".toByteArray())
        val bytes = "the real artifact".toByteArray()

        val target = store.write(jobId = 7, fileName = "ORD-10.pdf", bytes = bytes)

        assertContentEquals(bytes, Files.readAllBytes(target))
        assertEquals(listOf(target), root.resolve("7").listDirectoryEntries())
    }

    @Test
    fun `load verifies the digest`() {
        val bytes = "immutable artifact".toByteArray()
        store.write(jobId = 3, fileName = "ORD-20.pdf", bytes = bytes)

        val loaded =
            store.load(jobId = 3, fileName = "ORD-20.pdf", expectedSha256 = sha256Hex(bytes))

        assertContentEquals(bytes, assertIs<ProductionArtifactLoadResult.Loaded>(loaded).bytes)
    }

    @Test
    fun `load accepts a digest in any hex case`() {
        val bytes = "immutable artifact".toByteArray()
        store.write(jobId = 3, fileName = "ORD-20.pdf", bytes = bytes)

        val loaded =
            store.load(
                jobId = 3,
                fileName = "ORD-20.pdf",
                expectedSha256 = sha256Hex(bytes).uppercase(),
            )

        assertIs<ProductionArtifactLoadResult.Loaded>(loaded)
    }

    @Test
    fun `a missing artifact is a typed result`() {
        val loaded = store.load(jobId = 99, fileName = "ORD-1.pdf", expectedSha256 = sha256("x"))

        assertIs<ProductionArtifactLoadResult.Missing>(loaded)
    }

    @Test
    fun `tampered bytes are a digest mismatch carrying the actual digest`() {
        store.write(jobId = 3, fileName = "ORD-20.pdf", bytes = "original".toByteArray())
        Files.write(root.resolve("3").resolve("ORD-20.pdf"), "tampered".toByteArray())

        val loaded =
            store.load(jobId = 3, fileName = "ORD-20.pdf", expectedSha256 = sha256("original"))

        val mismatch = assertIs<ProductionArtifactLoadResult.DigestMismatch>(loaded)
        assertEquals(sha256("tampered"), mismatch.actualSha256)
    }

    @Test
    fun `path traversal in the file name and non-positive job ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            store.write(jobId = 1, fileName = "../escape.pdf", bytes = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.load(jobId = 1, fileName = "a/b.pdf", expectedSha256 = sha256("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.write(jobId = 0, fileName = "ORD-1.pdf", bytes = ByteArray(1))
        }
    }

    private fun sha256(text: String): String = sha256Hex(text.toByteArray())
}

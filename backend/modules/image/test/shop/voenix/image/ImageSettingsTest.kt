package shop.voenix.image

import io.ktor.server.config.MapApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class ImageSettingsTest {
    @Test
    fun `configuration requires roots and resolves relative paths`() =
        withTempDirectory { workingDirectory ->
            val config =
                MapApplicationConfig().apply {
                    put("Image.PublicRoot", "public")
                    put("Image.PrivateRoot", "private")
                    put("Image.CacheRoot", "cache")
                }

            val settings = ImageSettings.from(config, workingDirectory)

            assertEquals(workingDirectory.resolve("public").toRealPath(), settings.publicRoot)
            assertEquals(workingDirectory.resolve("private").toRealPath(), settings.privateRoot)
            assertEquals(workingDirectory.resolve("cache").toRealPath(), settings.cacheRoot)
            assertTrue(Files.isDirectory(settings.publicRoot))
        }

    @Test
    fun `missing blank file and overlapping roots fail startup`() = withTempDirectory { root ->
        assertFailsWith<IllegalStateException> { ImageSettings.from(MapApplicationConfig(), root) }

        val blank = validConfig(root).apply { put("Image.PublicRoot", "   ") }
        assertFailsWith<IllegalStateException> { ImageSettings.from(blank, root) }

        val file = Files.createFile(root.resolve("not-a-directory"))
        assertFailsWith<IllegalStateException> {
            ImageSettings.create(file, root.resolve("private-a"), root.resolve("cache-a"), root)
        }

        assertFailsWith<IllegalArgumentException> {
            ImageSettings.create(
                root.resolve("overlap"),
                root.resolve("overlap/private"),
                root.resolve("cache-b"),
                root,
            )
        }
    }

    @Test
    fun `unwritable roots fail startup when posix permissions are available`() =
        withTempDirectory { root ->
            val publicRoot = Files.createDirectory(root.resolve("public"))
            val original = Files.getPosixFilePermissions(publicRoot)
            try {
                Files.setPosixFilePermissions(
                    publicRoot,
                    PosixFilePermissions.fromString("r-xr-xr-x"),
                )
                if (!Files.isWritable(publicRoot)) {
                    assertFailsWith<IllegalStateException> {
                        ImageSettings.create(
                            publicRoot,
                            root.resolve("private"),
                            root.resolve("cache"),
                            root,
                        )
                    }
                }
            } finally {
                Files.setPosixFilePermissions(publicRoot, original)
            }
        }

    private fun validConfig(root: Path): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("Image.PublicRoot", root.resolve("public").toString())
            put("Image.PrivateRoot", root.resolve("private").toString())
            put("Image.CacheRoot", root.resolve("cache").toString())
        }

    private fun withTempDirectory(test: (Path) -> Unit) {
        val root = createTempDirectory("image-settings-test")
        try {
            test(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

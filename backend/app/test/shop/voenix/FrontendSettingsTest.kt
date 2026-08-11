package shop.voenix

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import shop.voenix.http.FrontendBaseUrl

internal class FrontendSettingsTest {
    /**
     * `frontend.baseUrl` is required in every environment — the mails are built from it — so every
     * configuration here carries it, and only `distPath` varies.
     */
    private fun frontendConfig(vararg entries: Pair<String, String>): MapApplicationConfig =
        MapApplicationConfig(*entries, "frontend.baseUrl" to BASE_URL.value)

    @Test
    fun `an empty distPath disables frontend serving`() {
        val settings = FrontendSettings.from(frontendConfig("frontend.distPath" to ""))
        assertNull(settings.distDirectory)
    }

    @Test
    fun `a missing distPath disables frontend serving`() {
        assertNull(FrontendSettings.from(frontendConfig()).distDirectory)
    }

    @Test
    fun `a distPath without an index page is refused`() {
        val directory = Files.createTempDirectory("frontend-dist").toFile()
        assertFailsWith<IllegalStateException> {
            FrontendSettings.from(frontendConfig("frontend.distPath" to directory.path))
        }
    }

    @Test
    fun `a distPath with an index page is accepted`() {
        val directory = distDirectory()
        val settings = FrontendSettings.from(frontendConfig("frontend.distPath" to directory.path))
        assertEquals(directory.absoluteFile.normalize(), settings.distDirectory)
    }

    @Test
    fun `the index page is served at the root and never cached`() = testApplication {
        val directory = distDirectory()
        application { installFrontendModule(FrontendSettings(BASE_URL, directory)) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("<!doctype html>frontend", response.bodyAsText())
        assertEquals("no-cache, no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a frontend router url falls back to the index page`() = testApplication {
        val directory = distDirectory()
        application { installFrontendModule(FrontendSettings(BASE_URL, directory)) }

        val response = client.get("/admin/orders")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("<!doctype html>frontend", response.bodyAsText())
    }

    @Test
    fun `a content-hashed asset is cached for a year`() = testApplication {
        val directory = distDirectory()
        application { installFrontendModule(FrontendSettings(BASE_URL, directory)) }

        val response = client.get("/assets/app-abc123.js")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "max-age=31536000, public, immutable",
            response.headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `a 3d model answers with the gltf binary content type`() = testApplication {
        val directory = distDirectory()
        application { installFrontendModule(FrontendSettings(BASE_URL, directory)) }

        val response = client.get("/assets/mug-abc123.glb")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("model/gltf-binary", response.headers[HttpHeaders.ContentType])
    }

    /**
     * A minimal Vite build output: the entry page plus one hashed asset of each interesting kind.
     */
    private fun distDirectory(): File {
        val directory = Files.createTempDirectory("frontend-dist").toFile()
        directory.resolve("index.html").writeText("<!doctype html>frontend")
        directory.resolve("assets").mkdir()
        directory.resolve("assets/app-abc123.js").writeText("console.log('frontend')")
        directory.resolve("assets/mug-abc123.glb").writeBytes(byteArrayOf(0x67, 0x6C, 0x54, 0x46))
        return directory
    }

    private companion object {
        val BASE_URL: FrontendBaseUrl = FrontendBaseUrl("http://localhost:5173")
    }
}

package shop.voenix

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import shop.voenix.auth.AuthRouting
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the generator wiring of the real composition root: the route is installed under the
 * guest-capable CSRF protection, the prompt catalog and the Magic Coins capability are bound to the
 * real modules, and one generation really costs one coin of a visitor who has no account.
 *
 * The composed application runs in dummy mode, so the image provider is the one thing this test
 * does not exercise — deliberately: the quality gate must never spend money at fal.ai, and what the
 * adapter sends is pinned by `FalImageGeneratorTest` against a mock engine.
 */
internal class GeneratorCompositionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("generator-composition-test")

    /** Reads the prompt and coin rows the journey asserts on; the application owns its own pool. */
    private var rows: HikariDataSource? = null

    @AfterTest
    fun closeRows() {
        rows?.close()
    }

    @Test
    fun `the composed application generates an image and charges one Magic Coin`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module() }
            startApplication()
            rows = dataSource("generator-composition-test", SCHEMA)
            val promptId = insertPrompt()

            val visitor = createClient { install(HttpCookies) }

            assertEquals(
                HttpStatusCode.BadRequest,
                visitor.post("/api/generator/generate").status,
                "the guest-capable CSRF protection really guards the composed subtree",
            )

            val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            val uploaded = pngBytes()

            val generated =
                visitor.post("/api/generator/generate") {
                    header(AuthRouting.CSRF_HEADER, csrf)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "image",
                                    uploaded,
                                    Headers.build {
                                        append(HttpHeaders.ContentType, "image/png")
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"upload.png\"",
                                        )
                                    },
                                )
                                append("promptId", promptId.toString())
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.OK, generated.status)
            assertEquals("image/png", generated.headers[HttpHeaders.ContentType])
            assertContentEquals(
                uploaded,
                generated.body<ByteArray>(),
                "dummy mode answers with the uploaded image itself, byte for byte",
            )
            assertTrue(
                generated.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { cookie ->
                    cookie.startsWith("voenix.guest=")
                },
                "a visitor without an account is given the guest identity their coins belong to",
            )

            assertEquals("1", singleValue("SELECT count(*) FROM $SCHEMA.magic_coins"))
            assertEquals(
                "9",
                singleValue("SELECT balance FROM $SCHEMA.magic_coins"),
                "the generation cost exactly one of the ten coins a new visitor starts with",
            )
        }

    /**
     * The one prompt the generation names, written directly: how a prompt is created is the prompt
     * module's own contract, while this test only needs an id the catalog answers a text for.
     */
    private fun insertPrompt(): Long {
        val categoryId =
            singleValue(
                "INSERT INTO $SCHEMA.prompt_categories (name, position, active) " +
                    "VALUES ('Composition', 1, true) RETURNING id"
            )
        return singleValue(
                "INSERT INTO $SCHEMA.prompts " +
                    "(position, title, prompt_text, category_id, active, archived) " +
                    "VALUES (1, 'Composition', 'Ein Mops im Weltall', $categoryId, true, false) " +
                    "RETURNING id"
            )
            .toLong()
    }

    private fun singleValue(sql: String): String =
        checkNotNull(rows).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "No row for $sql" }
                    result.getString(1)
                }
            }
        }

    private fun applicationConfig(): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", SCHEMA)
            put("database.sslMode", "Disable")
            put("database.maximumPoolSize", "2")
            put("auth.sessionSecret", "generator-composition-test-session-secret")
            put("account.frontendBaseUrl", "http://localhost:5173")
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            // The composed application is wired to Mollie but never calls it: no test here
            // starts a payment, and the webhook route only needs the secret to reject one.
            put("mollie.apiKey", "test_composition_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put(
                "mollie.webhookUrl",
                "https://voenix.test/api/payments/webhook/composition-test-webhook-secret",
            )
            put("mollie.webhookSecret", "composition-test-webhook-secret")
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
        }

    /**
     * The app module has no JSON parser on its test classpath; one field is read with one regex.
     */
    private fun String.field(name: String): String =
        assertNotNull(Regex("\"$name\"\\s*:\\s*\"?([^\",}]+)\"?").find(this), "No field $name")
            .groupValues[1]

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private companion object {
        const val SCHEMA = "generator_composition_test"
    }
}

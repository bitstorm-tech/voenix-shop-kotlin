package shop.voenix.prompt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The example image of a prompt against real Ktor routes and a real PostgreSQL database: what the
 * pre-upload answers, when a submitted file name is checked, and which file a write may delete once
 * it has committed.
 *
 * It is a slice of its own because the deletion is a cross-row question. A name is not exclusive —
 * the pre-upload hands a client one name, and it may put that name on two prompts — so whether a
 * dropped file really became an orphan is decided by every other prompt of the table.
 */
internal class PromptExampleImageIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the pre-upload answers with the stored name and a rejected write leaves it behind`() {
        migratedDataSource("prompt-image-upload-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-image-upload-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)

                val uploaded = admin.uploadExampleImage(token)
                assertEquals(HttpStatusCode.Created, uploaded.status)
                val body = Json.parseToJsonElement(uploaded.bodyAsText()).jsonObject
                assertEquals(setOf("filename"), body.keys)
                assertEquals(FIRST_IMAGE, body.text("filename"))
                assertEquals(listOf(FIRST_IMAGE), images.files)

                // The write that was supposed to use the name is rejected for another reason. The
                // file stays behind as an accepted orphan; nothing sweeps it here.
                assertFieldError(
                    admin.createPrompt(token, promptBody(categoryId = 404, image = FIRST_IMAGE)),
                    "categoryId",
                    "Prompt category does not exist",
                )
                assertEquals(emptyList(), PromptTestSchema.orderedPrompts(dataSource))
                assertEquals(emptyList(), images.deleted)
                assertEquals(listOf(FIRST_IMAGE), images.files)
            }
        }
    }

    @Test
    fun `an example image is checked while the prompt is saved`() {
        migratedDataSource("prompt-image-check-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-image-check-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                // Only the shape the image storage mints is accepted — the legacy contract also
                // took `picture.png`, and no name of that kind can exist here.
                assertFieldError(
                    admin.createPrompt(token, promptBody(image = "picture.webp")),
                    EXAMPLE_IMAGE_FIELD,
                    "Example image filename must be the name of an uploaded image",
                )
                assertFieldError(
                    admin.createPrompt(token, promptBody(image = SECOND_IMAGE)),
                    EXAMPLE_IMAGE_FIELD,
                    "Example image does not exist",
                )
                assertEquals(emptyList(), PromptTestSchema.orderedPrompts(dataSource))

                val created = admin.createPrompt(token, promptBody(image = FIRST_IMAGE))
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")

                // A name the prompt already stores is checked again. A file is only removed once
                // no prompt names it, so a file that is gone was deleted by another writer who
                // replaced it — and writing the name back would point the row at nothing.
                images.sweep(FIRST_IMAGE)
                assertFieldError(
                    admin.updatePrompt(token, id.toLong(), promptBody(image = FIRST_IMAGE)),
                    EXAMPLE_IMAGE_FIELD,
                    "Example image does not exist",
                )

                // The same body is accepted again as soon as the file is back.
                images.put(FIRST_IMAGE)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updatePrompt(token, id.toLong(), promptBody(image = FIRST_IMAGE)).status,
                )
            }
        }
    }

    @Test
    fun `a shared example image survives the prompt that drops it`() {
        migratedDataSource("prompt-shared-image-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-shared-image-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                val first = admin.createPrompt(token, promptBody(image = FIRST_IMAGE))
                val second =
                    admin.createPrompt(
                        token,
                        promptBody(title = "Second prompt", image = FIRST_IMAGE),
                    )
                assertEquals(HttpStatusCode.Created, first.status)
                assertEquals(HttpStatusCode.Created, second.status)
                val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject.number("id")
                val secondId = Json.parseToJsonElement(second.bodyAsText()).jsonObject.number("id")

                // The first prompt drops the name while the second still shows the picture.
                val cleared = admin.updatePrompt(token, firstId.toLong(), promptBody())
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertEquals(null, storedExampleImageFilename(dataSource, firstId.toLong()))
                assertEquals(emptyList(), images.deleted)
                assertEquals(listOf(FIRST_IMAGE), images.files)

                // Only the reference that really was the last one takes the file with it.
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updatePrompt(token, secondId.toLong(), promptBody(title = "Second prompt"))
                        .status,
                )
                assertEquals(listOf(FIRST_IMAGE), images.deleted)
                assertEquals(emptyList(), images.files)
            }
        }
    }

    /**
     * The file is the module's own business: whether it could be removed says nothing about whether
     * the prompt was written, so a failing delete is logged and the client sees the write it asked
     * for.
     */
    @Test
    fun `a failing cleanup does not fail the request`() {
        migratedDataSource("prompt-image-cleanup-failure-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "prompt-image-cleanup-integration-session-secret",
                RecordingPublicImageStorage(failingDeletes = true),
            ) { admin, images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                val created = admin.createPrompt(token, promptBody(image = FIRST_IMAGE))
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")

                val cleared = admin.updatePrompt(token, id.toLong(), promptBody())
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertEquals(listOf(FIRST_IMAGE), images.deleted)
                // The delete was attempted and failed, so the file is still there — and the prompt
                // no longer refers to it.
                assertEquals(listOf(FIRST_IMAGE), images.files)
                assertEquals(null, storedExampleImageFilename(dataSource, id.toLong()))
            }
        }
    }

    /** The category and the VAT entry every price refers to. */
    private fun seedCatalog(dataSource: DataSource) {
        PromptTestSchema.reset(dataSource)
        PromptTestSchema.seedVat(dataSource)
        PromptTestSchema.seedCategories(dataSource, "Portraits")
    }

    private fun promptBody(
        title: String = "Watercolor portrait",
        categoryId: Long = 1,
        image: String? = null,
    ): String =
        """{"title":"$title","promptText":"Turn the photo into art.","categoryId":$categoryId,""" +
            """"slotVariantIds":[]""" +
            (image?.let { file -> ""","exampleImageFilename":"$file"""" } ?: "") +
            ""","active":true,"archived":false,""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499}}"""

    private fun storedExampleImageFilename(
        dataSource: DataSource,
        promptId: Long,
    ): String? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT example_image_filename FROM voenix.prompts WHERE id = $promptId"
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getString("example_image_filename")
                    }
                }
        }

    private suspend fun HttpClient.createPrompt(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updatePrompt(
        token: String,
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.uploadExampleImage(token: String): HttpResponse =
        post("$BASE_PATH/example-images") {
            header(AuthRouting.CSRF_HEADER, token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            ByteArray(16) { 1 },
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"example.png\"")
                            },
                        )
                    }
                )
            )
        }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        images: RecordingPublicImageStorage = RecordingPublicImageStorage(),
        block: suspend (HttpClient, RecordingPublicImageStorage) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) { validatePromptRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            installPromptModule(
                database,
                images,
                installPricingModule(database, installVatModule(database)),
            )
            routing {
                post("/test/sign-in") {
                    call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val admin = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
        block(admin, images)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private suspend fun assertFieldError(
        response: HttpResponse,
        field: String,
        message: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        val errors =
            body.getValue("errors").jsonObject.mapValues { (_, messages) ->
                messages.jsonArray.map { entry -> entry.jsonPrimitive.content }
            }
        assertTrue(field in errors, "Expected an error on $field but got $errors")
        assertEquals(listOf(message), errors[field])
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/prompts"
        const val EXAMPLE_IMAGE_FIELD = "exampleImageFilename"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
    }
}

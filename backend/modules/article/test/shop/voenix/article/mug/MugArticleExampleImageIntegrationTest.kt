package shop.voenix.article.mug

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.RecordingSupplierReader
import shop.voenix.article.installArticleModule
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The example image of a mug variant against real Ktor routes and a real PostgreSQL database: when
 * a submitted file name is checked, and which file a write may delete once it has committed.
 *
 * It is a slice of its own because both questions are cross-row questions. The variant array is a
 * diff, so which file became an orphan is decided by the whole array, and whether a file really is
 * an orphan is decided by every other variant of the table.
 */
internal class MugArticleExampleImageIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the variant array is a diff and deletes the images it orphans`() {
        migratedDataSource("article-mug-variant-diff-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-variant-diff-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE)

                val created =
                    admin.createMug(
                        token,
                        mugBody(
                            "Classic mug",
                            variantBody("Black", "#000", isDefault = false, image = SECOND_IMAGE),
                            variantBody("White", "#fff", isDefault = true, image = FIRST_IMAGE),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val id = body.number("id").toLong()
                val defaultVariantId =
                    body.getValue("mugVariants").jsonArray[0].jsonObject.number("id")

                // Keep the first with a new image, drop the second, add a third, and move the
                // default flag — a swap the partial unique index would reject in the wrong order.
                val updated =
                    admin.updateMug(
                        token,
                        id,
                        mugBody(
                            "Classic mug",
                            variantBody(
                                "White",
                                "#fff",
                                isDefault = false,
                                image = THIRD_IMAGE,
                                id = defaultVariantId.toLong(),
                            ),
                            variantBody("Blue", "#00f", isDefault = true),
                        ),
                    )
                assertEquals(HttpStatusCode.OK, updated.status)

                val stored = ArticleTestSchema.storedVariants(dataSource, id)
                assertEquals(listOf("White" to THIRD_IMAGE, "Blue" to null), stored)
                // Both orphans are gone: the image the removed variant held and the one the kept
                // variant replaced. The image of the removed variant was the second one.
                assertEquals(setOf(FIRST_IMAGE, SECOND_IMAGE), images.deleted.toSet())
                assertEquals(listOf(THIRD_IMAGE), images.files)

                // A variant of another article cannot be addressed through this one.
                val other = admin.createMug(token, mugBody("Other mug"))
                val otherId = Json.parseToJsonElement(other.bodyAsText()).jsonObject.number("id")
                assertFieldError(
                    admin.updateMug(
                        token,
                        otherId.toLong(),
                        mugBody(
                            "Other mug",
                            variantBody(
                                "White",
                                "#fff",
                                isDefault = true,
                                id = defaultVariantId.toLong(),
                            ),
                        ),
                    ),
                    "mugVariants",
                    "One or more variants do not belong to this article",
                )
            }
        }
    }

    @Test
    fun `an example image is checked while the mug is saved`() {
        migratedDataSource("article-mug-image-check-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-image-check-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                assertFieldError(
                    admin.createMug(
                        token,
                        mugBody(
                            "Ghost",
                            variantBody("White", "#fff", isDefault = true, image = "picture.webp"),
                        ),
                    ),
                    "mugVariants[0].exampleImageFilename",
                    "Example image filename must be the name of an uploaded image",
                )
                assertFieldError(
                    admin.createMug(
                        token,
                        mugBody(
                            "Ghost",
                            variantBody("White", "#fff", isDefault = true, image = SECOND_IMAGE),
                        ),
                    ),
                    "mugVariants[0].exampleImageFilename",
                    "Example image does not exist",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedMugs(dataSource))

                val created =
                    admin.createMug(
                        token,
                        mugBody(
                            "Classic",
                            variantBody("White", "#fff", isDefault = true, image = FIRST_IMAGE),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")

                // A name the variant already stores is checked again. The deferred sweep only
                // removes files no row names, so a file that is gone was deleted by another writer
                // who replaced it — and writing the name back would point the row at nothing.
                images.sweep(FIRST_IMAGE)
                val variantId = storedVariantId(dataSource, id.toLong())
                val resubmitted =
                    mugBody(
                        "Classic",
                        variantBody(
                            "White",
                            "#fff",
                            isDefault = true,
                            image = FIRST_IMAGE,
                            id = variantId,
                        ),
                    )
                assertFieldError(
                    admin.updateMug(token, id.toLong(), resubmitted),
                    "mugVariants[0].exampleImageFilename",
                    "Example image does not exist",
                )

                // The same body is accepted again as soon as the file is back.
                images.put(FIRST_IMAGE)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateMug(token, id.toLong(), resubmitted).status,
                )
            }
        }
    }

    /**
     * Nothing makes an example image name exclusive: the pre-upload hands a client one name, and it
     * may put that name on two variants. A variant that drops it must not delete the picture the
     * other one still shows.
     */
    @Test
    fun `a shared example image survives the variant that drops it`() {
        migratedDataSource("article-mug-shared-image-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-shared-image-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                val created =
                    admin.createMug(
                        token,
                        mugBody(
                            "Classic mug",
                            variantBody("White", "#fff", isDefault = true, image = FIRST_IMAGE),
                            variantBody("Black", "#000", isDefault = false, image = FIRST_IMAGE),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val id = body.number("id").toLong()
                // The default variant comes first, and it is the one that keeps the image.
                val keptId =
                    body.getValue("mugVariants").jsonArray[0].jsonObject.number("id").toLong()

                val kept =
                    variantBody("White", "#fff", isDefault = true, image = FIRST_IMAGE, id = keptId)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateMug(token, id, mugBody("Classic mug", kept)).status,
                )
                assertEquals(emptyList(), images.deleted)
                assertEquals(listOf(FIRST_IMAGE), images.files)

                // Only the reference that really was the last one takes the file with it.
                val cleared = variantBody("White", "#fff", isDefault = true, id = keptId)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateMug(token, id, mugBody("Classic mug", cleared)).status,
                )
                assertEquals(listOf(FIRST_IMAGE), images.deleted)
                assertEquals(emptyList(), images.files)
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")
    }

    /** A draft mug that carries nothing but its texts and the [variants] the test is about. */
    private fun mugBody(
        name: String,
        vararg variants: String,
    ): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"mugVariants":[${variants.joinToString(",")}]}"""

    private fun variantBody(
        name: String,
        colorCode: String,
        isDefault: Boolean,
        image: String? = null,
        id: Long? = null,
    ): String =
        """{${id?.let { value -> """"id":$value,""" } ?: ""}"name":"$name",""" +
            """"insideColorCode":"$colorCode","outsideColorCode":"$colorCode",""" +
            """"isDefault":$isDefault""" +
            (image?.let { file -> ""","exampleImageFilename":"$file"""" } ?: "") +
            "}"

    private fun storedVariantId(
        dataSource: DataSource,
        articleId: Long,
    ): Long =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT id FROM voenix.article_mug_variants WHERE article_id = $articleId"
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong("id")
                    }
                }
        }

    private suspend fun HttpClient.createMug(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateMug(
        token: String,
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (HttpClient, RecordingPublicImageStorage) -> Unit,
    ) = testApplication {
        val images = RecordingPublicImageStorage()
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            installArticleModule(
                database,
                images,
                installPricingModule(database, installVatModule(database)),
                RecordingSupplierReader(),
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
        const val BASE_PATH = "/api/admin/articles/mugs"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
        const val THIRD_IMAGE = "33333333-3333-4333-8333-333333333333.webp"
    }
}

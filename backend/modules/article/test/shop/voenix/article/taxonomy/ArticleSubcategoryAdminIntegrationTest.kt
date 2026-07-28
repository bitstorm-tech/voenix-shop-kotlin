package shop.voenix.article.taxonomy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

internal class ArticleSubcategoryAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends inside its category and lists by category order`() {
        migratedDataSource("article-subcategory-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")

            adminApplication(dataSource, "article-subcategory-create-session-secret") { admin, _ ->
                val token = antiforgeryToken(admin)

                val created =
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"  Classic  ","description":"  Plain  "}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Classic", body.text("name"))
                assertEquals("Plain", body.text("description"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("position"))
                assertEquals("true", body.text("active"))
                assertEquals(null, body["exampleImageFilename"]?.jsonPrimitive?.contentOrNull)
                assertEquals(
                    "/api/admin/articles/subcategories/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                admin.createSubcategory(token, """{"categoryId":1,"name":"Magic"}""")
                // The same name is free again in another category.
                admin.createSubcategory(
                    token,
                    """{"categoryId":2,"name":"Classic","active":false}""",
                )

                assertEquals(
                    listOf("Classic" to 1, "Magic" to 2),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Classic" to 1),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 2),
                )

                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf(1 to "Classic", 1 to "Magic", 2 to "Classic"),
                    listed.map { it.jsonObject.number("categoryId") to it.jsonObject.text("name") },
                )
            }
        }
    }

    @Test
    fun `a name that differs only in case is rejected inside the same category`() {
        migratedDataSource("article-subcategory-duplicate-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic", "Magic")

            adminApplication(dataSource, "article-subcategory-duplicate-session-secret") { admin, _
                ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.createSubcategory(token, """{"categoryId":1,"name":" cLASSIC "}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertApiMessage(
                    admin.updateSubcategory(
                        token,
                        id = 2,
                        body = """{"categoryId":1,"name":"CLASSIC"}""",
                    ),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSubcategory(token, """{"categoryId":2,"name":"CLASSIC"}""").status,
                )
            }
        }
    }

    @Test
    fun `read update and delete report a missing subcategory as not found`() {
        migratedDataSource("article-subcategory-missing-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")

            adminApplication(dataSource, "article-subcategory-missing-session-secret") { admin, _ ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.get("$BASE_PATH/404"),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.updateSubcategory(
                        token,
                        id = 404,
                        body = """{"categoryId":1,"name":"Ghost"}""",
                    ),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.delete("$BASE_PATH/404") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
            }
        }
    }

    @Test
    fun `an unknown category is a field error instead of a conflict`() {
        migratedDataSource("article-subcategory-unknown-category-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")

            adminApplication(dataSource, "article-subcategory-unknown-category-secret") { admin, _
                ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createSubcategory(token, """{"categoryId":404,"name":"Ghost"}"""),
                    "categoryId",
                    "Article category does not exist",
                )
                assertFieldError(
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":404,"name":"Classic"}""",
                    ),
                    "categoryId",
                    "Article category does not exist",
                )
            }
        }
    }

    @Test
    fun `a category change appends in the target category and compacts the source`() {
        migratedDataSource("article-subcategory-move-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "First", "Second")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Only")

            adminApplication(dataSource, "article-subcategory-move-session-secret") { admin, _ ->
                val token = antiforgeryToken(admin)

                val moved =
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":2,"name":"First","active":false}""",
                    )
                assertEquals(HttpStatusCode.OK, moved.status)
                val body = Json.parseToJsonElement(moved.bodyAsText()).jsonObject
                assertEquals(2, body.number("categoryId"))
                assertEquals(2, body.number("position"))
                assertEquals("false", body.text("active"))

                assertEquals(
                    listOf("Second" to 1),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Only" to 1, "First" to 2),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 2),
                )
            }
        }
    }

    /**
     * The composite foreign key `(subcategory_id, category_id)` of `article_mugs` is what forbids
     * the move; no query asks whether an article uses the subcategory first.
     */
    @Test
    fun `a subcategory an article uses can neither be moved nor deleted`() {
        migratedDataSource("article-subcategory-in-use-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic", "Magic")

            adminApplication(dataSource, "article-subcategory-in-use-session-secret") { admin, _ ->
                val token = antiforgeryToken(admin)
                // The article is written through the mug routes, so what makes the subcategory
                // "in use" is a real article rather than a hand-written row.
                assertEquals(
                    HttpStatusCode.Created,
                    admin
                        .post("/api/admin/articles/mugs") {
                            header(AuthRouting.CSRF_HEADER, token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"name":"Mug","descriptionShort":"Short",""" +
                                    """"descriptionLong":"Long","categoryId":1,"subcategoryId":1}"""
                            )
                        }
                        .status,
                )

                assertFieldError(
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":2,"name":"Classic"}""",
                    ),
                    "categoryId",
                    "Article subcategory is used by articles and cannot be moved to another category",
                )
                assertApiMessage(
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Article subcategory is used by articles and cannot be deleted",
                )

                // Neither rejection changed anything, and renaming inside the category still works.
                assertEquals(
                    listOf("Classic" to 1, "Magic" to 2),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateSubcategory(
                            token,
                            id = 1,
                            body = """{"categoryId":1,"name":"Classic mugs"}""",
                        )
                        .status,
                )
            }
        }
    }

    @Test
    fun `delete closes the gap and removes the example image`() {
        migratedDataSource("article-subcategory-delete-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "First", "Second")
            ArticleTestSchema.execute(
                dataSource,
                """
                UPDATE voenix.article_subcategories
                SET example_image_filename = '${RecordingPublicImageStorage.FIRST_FILENAME}'
                WHERE id = 1
                """
                    .trimIndent(),
            )

            adminApplication(dataSource, "article-subcategory-delete-session-secret") {
                admin,
                images ->
                images.put(RecordingPublicImageStorage.FIRST_FILENAME)
                val token = antiforgeryToken(admin)

                val deleted =
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(
                    listOf("Second" to 1),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(listOf(RecordingPublicImageStorage.FIRST_FILENAME), images.deleted)
                assertEquals(emptyList(), images.files)
            }
        }
    }

    @Test
    fun `an example image is checked when it is saved and replaced only after the commit`() {
        migratedDataSource("article-subcategory-image-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")

            adminApplication(dataSource, "article-subcategory-image-session-secret") { admin, images
                ->
                val token = antiforgeryToken(admin)
                images.put(
                    RecordingPublicImageStorage.FIRST_FILENAME,
                    RecordingPublicImageStorage.SECOND_FILENAME,
                )

                assertFieldError(
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"Classic","exampleImageFilename":"picture.webp"}""",
                    ),
                    "exampleImageFilename",
                    "Example image filename must be the name of an uploaded image",
                )
                assertFieldError(
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"Classic",""" +
                            """"exampleImageFilename":"33333333-3333-4333-8333-333333333333.webp"}""",
                    ),
                    "exampleImageFilename",
                    "Example image does not exist",
                )

                val created =
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"Classic",""" +
                            """"exampleImageFilename":"${RecordingPublicImageStorage.FIRST_FILENAME}"}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals(
                    RecordingPublicImageStorage.FIRST_FILENAME,
                    Json.parseToJsonElement(created.bodyAsText())
                        .jsonObject
                        .text("exampleImageFilename"),
                )

                // A name the row already stores is checked again. The deferred sweep only removes
                // files no row names, so a file that is gone was deleted by another writer who
                // replaced it — and writing the name back would point the row at nothing.
                images.sweep(RecordingPublicImageStorage.FIRST_FILENAME)
                assertFieldError(
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body =
                            """{"categoryId":1,"name":"Classic",""" +
                                """"exampleImageFilename":"${RecordingPublicImageStorage.FIRST_FILENAME}"}""",
                    ),
                    "exampleImageFilename",
                    "Example image does not exist",
                )
                images.put(RecordingPublicImageStorage.FIRST_FILENAME)

                // A rejected write leaves the uploaded file behind: an accepted orphan.
                assertApiMessage(
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"classic",""" +
                            """"exampleImageFilename":"${RecordingPublicImageStorage.SECOND_FILENAME}"}""",
                    ),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertEquals(emptyList(), images.deleted)

                // Replacing the image deletes the file the subcategory stopped referring to.
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateSubcategory(
                            token,
                            id = 1,
                            body =
                                """{"categoryId":1,"name":"Classic",""" +
                                    """"exampleImageFilename":"${RecordingPublicImageStorage.SECOND_FILENAME}"}""",
                        )
                        .status,
                )
                assertEquals(listOf(RecordingPublicImageStorage.FIRST_FILENAME), images.deleted)

                // A missing file name removes the image and deletes the file.
                val removed =
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":1,"name":"Classic"}""",
                    )
                assertEquals(HttpStatusCode.OK, removed.status)
                assertEquals(
                    null,
                    Json.parseToJsonElement(removed.bodyAsText())
                        .jsonObject["exampleImageFilename"]
                        ?.jsonPrimitive
                        ?.contentOrNull,
                )
                assertEquals(
                    listOf(
                        RecordingPublicImageStorage.FIRST_FILENAME,
                        RecordingPublicImageStorage.SECOND_FILENAME,
                    ),
                    images.deleted,
                )
                assertEquals(emptyList(), images.files)
            }
        }
    }

    /**
     * Nothing makes an example image name exclusive: the pre-upload hands a client one name, and it
     * may put that name into two bodies. A subcategory that drops it must not delete the picture
     * the other one still shows.
     */
    @Test
    fun `a shared example image survives the subcategory that drops it`() {
        migratedDataSource("article-subcategory-shared-image-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")

            adminApplication(dataSource, "article-subcategory-shared-image-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(RecordingPublicImageStorage.FIRST_FILENAME)

                val shared =
                    """"exampleImageFilename":"${RecordingPublicImageStorage.FIRST_FILENAME}""""
                assertEquals(
                    HttpStatusCode.Created,
                    admin
                        .createSubcategory(token, """{"categoryId":1,"name":"First",$shared}""")
                        .status,
                )
                assertEquals(
                    HttpStatusCode.Created,
                    admin
                        .createSubcategory(token, """{"categoryId":1,"name":"Second",$shared}""")
                        .status,
                )

                // The first one drops the image while the second still refers to it.
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateSubcategory(
                            token,
                            id = 1,
                            body = """{"categoryId":1,"name":"First"}""",
                        )
                        .status,
                )
                assertEquals(emptyList(), images.deleted)
                assertEquals(listOf(RecordingPublicImageStorage.FIRST_FILENAME), images.files)

                // Deleting the last row that names it takes the file with it.
                val deleted =
                    admin.delete("$BASE_PATH/2") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(listOf(RecordingPublicImageStorage.FIRST_FILENAME), images.deleted)
                assertEquals(emptyList(), images.files)
            }
        }
    }

    @Test
    fun `reorder answers with the dense order of the affected category`() {
        migratedDataSource("article-subcategory-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
            ArticleTestSchema.seedSubcategories(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
                "Fourth",
            )
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Other")

            adminApplication(dataSource, "article-subcategory-reorder-session-secret") { admin, _ ->
                val token = antiforgeryToken(admin)

                val reordered = admin.reorder(token, """{"sourceId":4,"targetId":2}""")
                assertEquals(HttpStatusCode.OK, reordered.status)
                val order = Json.parseToJsonElement(reordered.bodyAsText()).jsonArray
                assertEquals(
                    listOf("First", "Fourth", "Second", "Third"),
                    order.map { it.jsonObject.text("name") },
                )
                assertEquals(listOf(1, 2, 3, 4), order.map { it.jsonObject.number("position") })
                assertEquals(
                    listOf("First" to 1, "Fourth" to 2, "Second" to 3, "Third" to 4),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )

                // A target from another category is outside the ordered list, like an unknown id.
                assertApiMessage(
                    admin.reorder(token, """{"sourceId":1,"targetId":5}"""),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.reorder(token, """{"sourceId":404,"targetId":1}"""),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.reorder(token, """{"sourceId":1,"targetId":404}"""),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertEquals(
                    listOf("First" to 1, "Fourth" to 2, "Second" to 3, "Third" to 4),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Other" to 1),
                    ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 2),
                )
            }
        }
    }

    private suspend fun HttpClient.createSubcategory(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateSubcategory(
        token: String,
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.reorder(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/order") {
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

    private suspend fun assertApiMessage(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
    ) {
        assertEquals(status, response.status)
        assertEquals(
            message,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.text("message"),
        )
    }

    private suspend fun assertFieldError(
        response: HttpResponse,
        field: String,
        message: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        assertEquals(
            listOf(message),
            body.getValue("errors").jsonObject.getValue(field).jsonArray.map {
                it.jsonPrimitive.content
            },
        )
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/articles/subcategories"
        const val NOT_FOUND_MESSAGE = "Article subcategory not found"
        const val NAME_CONFLICT_MESSAGE =
            "Article subcategory name already exists in this article category"
    }
}

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

internal class ArticleCategoryAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends behind the last category and normalizes its values`() {
        migratedDataSource("article-category-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource, "article-category-create-integration-session-secret") {
                admin ->
                val token = antiforgeryToken(admin)

                val created =
                    admin.createCategory(token, """{"name":"  Mugs  ","description":"  Cups  "}""")
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Mugs", body.text("name"))
                assertEquals("Cups", body.text("description"))
                assertEquals(1, body.number("position"))
                assertEquals("true", body.text("active"))
                assertEquals(
                    "/api/admin/articles/categories/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createCategory(token, """{"name":"Posters","active":false}""").status,
                )
                assertEquals(
                    listOf("Mugs" to 1, "Posters" to 2),
                    ArticleTestSchema.orderedCategories(dataSource),
                )

                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(listOf("Mugs", "Posters"), listed.map { it.jsonObject.text("name") })
                assertEquals(
                    null,
                    listed[1].jsonObject["description"]?.jsonPrimitive?.contentOrNull,
                )
                assertEquals("false", listed[1].jsonObject.text("active"))
            }
        }
    }

    @Test
    fun `a name that differs only in case is rejected as a conflict`() {
        migratedDataSource("article-category-duplicate-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")

            adminApplication(dataSource, "article-category-duplicate-integration-session-secret") {
                admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.createCategory(token, """{"name":" mUGS "}"""),
                    HttpStatusCode.Conflict,
                    "Article category name already exists",
                )
                assertApiMessage(
                    admin.updateCategory(token, id = 2, body = """{"name":"MUGS"}"""),
                    HttpStatusCode.Conflict,
                    "Article category name already exists",
                )
                assertEquals(
                    listOf("Mugs" to 1, "Posters" to 2),
                    ArticleTestSchema.orderedCategories(dataSource),
                )
            }
        }
    }

    @Test
    fun `read update and delete report a missing category as not found`() {
        migratedDataSource("article-category-missing-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource, "article-category-missing-integration-session-secret") {
                admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.get("$BASE_PATH/404"),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.updateCategory(token, id = 404, body = """{"name":"Ghost"}"""),
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
    fun `update replaces every value and keeps the display position`() {
        migratedDataSource("article-category-update-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")

            adminApplication(dataSource, "article-category-update-integration-session-secret") {
                admin ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.updateCategory(
                        token,
                        id = 2,
                        body = """{"name":" Prints ","description":"  ","active":false}""",
                    )
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Prints", body.text("name"))
                assertEquals(null, body["description"]?.jsonPrimitive?.contentOrNull)
                assertEquals("false", body.text("active"))
                assertEquals(2, body.number("position"))
            }
        }
    }

    @Test
    fun `delete closes the gap and is blocked while a subcategory references the category`() {
        migratedDataSource("article-category-delete-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third")
            ArticleTestSchema.execute(
                dataSource,
                """
                INSERT INTO voenix.article_subcategories (id, category_id, name, position)
                VALUES (1, 3, 'Classic', 1)
                """
                    .trimIndent(),
            )

            adminApplication(dataSource, "article-category-delete-integration-session-secret") {
                admin ->
                val token = antiforgeryToken(admin)

                val deleted =
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(
                    listOf("Second" to 1, "Third" to 2),
                    ArticleTestSchema.orderedCategories(dataSource),
                )

                assertApiMessage(
                    admin.delete("$BASE_PATH/3") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Article category is used by subcategories or articles and cannot be deleted",
                )
                assertEquals(
                    listOf("Second" to 1, "Third" to 2),
                    ArticleTestSchema.orderedCategories(dataSource),
                )
            }
        }
    }

    @Test
    fun `reorder moves one category and answers with the complete dense order`() {
        migratedDataSource("article-category-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")

            adminApplication(dataSource, "article-category-reorder-integration-session-secret") {
                admin ->
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
                    ArticleTestSchema.orderedCategories(dataSource),
                )

                // Moving the first category behind the last one keeps the sequence dense.
                admin.reorder(token, """{"sourceId":1,"targetId":3}""")
                assertEquals(
                    listOf("Fourth" to 1, "Second" to 2, "Third" to 3, "First" to 4),
                    ArticleTestSchema.orderedCategories(dataSource),
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
                    listOf("Fourth" to 1, "Second" to 2, "Third" to 3, "First" to 4),
                    ArticleTestSchema.orderedCategories(dataSource),
                )
            }
        }
    }

    private suspend fun HttpClient.createCategory(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateCategory(
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
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            installArticleModule(
                database,
                RecordingPublicImageStorage(),
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
        block(admin)
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

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/articles/categories"
        const val NOT_FOUND_MESSAGE = "Article category not found"
    }
}

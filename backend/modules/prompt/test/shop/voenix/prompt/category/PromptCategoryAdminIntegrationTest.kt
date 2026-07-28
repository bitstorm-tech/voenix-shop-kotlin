package shop.voenix.prompt.category

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The real category routes on real PostgreSQL: how the dense display order is built, kept, and
 * defended.
 */
internal class PromptCategoryAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends behind the last category and normalizes its name`() {
        migratedDataSource("prompt-category-create-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-category-create-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val created = admin.createCategory(token, """{"name":"  Portraits  "}""")
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Portraits", body.text("name"))
                assertEquals(1, body.number("position"))
                assertEquals("true", body.text("active"))
                assertEquals(
                    "/api/admin/prompts/categories/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createCategory(token, """{"name":"Animals","active":false}""").status,
                )
                assertEquals(
                    listOf("Portraits" to 1, "Animals" to 2),
                    PromptTestSchema.orderedCategories(dataSource),
                )

                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf("Portraits", "Animals"),
                    listed.map { row -> row.jsonObject.text("name") },
                )
                assertEquals("false", listed[1].jsonObject.text("active"))
            }
        }
    }

    @Test
    fun `a name that differs only in case is rejected as a conflict`() {
        migratedDataSource("prompt-category-duplicate-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")

            adminApplication(dataSource, "prompt-category-duplicate-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.createCategory(token, """{"name":" pORTRAITS "}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertApiMessage(
                    admin.updateCategory(token, id = 2, body = """{"name":"PORTRAITS"}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertEquals(
                    listOf("Portraits" to 1, "Animals" to 2),
                    PromptTestSchema.orderedCategories(dataSource),
                )
            }
        }
    }

    @Test
    fun `read update and delete report a missing category as not found`() {
        migratedDataSource("prompt-category-missing-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-category-missing-session-secret") { admin ->
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
    fun `update replaces name and activation and keeps the display position`() {
        migratedDataSource("prompt-category-update-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")

            adminApplication(dataSource, "prompt-category-update-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.updateCategory(
                        token,
                        id = 2,
                        body = """{"name":"  Pets  ","active":false}""",
                    )
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Pets", body.text("name"))
                assertEquals(2, body.number("position"))
                assertEquals("false", body.text("active"))
            }
        }
    }

    @Test
    fun `delete closes the gap it leaves and is blocked while the category is referenced`() {
        migratedDataSource("prompt-category-delete-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids")
            PromptTestSchema.seedPromptIn(dataSource, categoryId = 4)

            adminApplication(dataSource, "prompt-category-delete-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Prompt category is used by subcategories or prompts and cannot be deleted",
                )
                assertApiMessage(
                    admin.delete("$BASE_PATH/4") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Prompt category is used by subcategories or prompts and cannot be deleted",
                )

                assertEquals(
                    HttpStatusCode.NoContent,
                    admin.delete("$BASE_PATH/2") { header(AuthRouting.CSRF_HEADER, token) }.status,
                )

                // Unlike a slot, a deleted category leaves no gap behind.
                assertEquals(
                    listOf("First" to 1, "Third" to 2, "Fourth" to 3),
                    PromptTestSchema.orderedCategories(dataSource),
                )
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createCategory(token, """{"name":"Fifth"}""").status,
                )
                assertEquals(
                    listOf("First" to 1, "Third" to 2, "Fourth" to 3, "Fifth" to 4),
                    PromptTestSchema.orderedCategories(dataSource),
                )
            }
        }
    }

    @Test
    fun `reorder answers with the complete new order and refuses an unknown id`() {
        migratedDataSource("prompt-category-reorder-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")

            adminApplication(dataSource, "prompt-category-reorder-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val reordered = admin.reorder(token, """{"sourceId":4,"targetId":1}""")
                assertEquals(HttpStatusCode.OK, reordered.status)
                val order = Json.parseToJsonElement(reordered.bodyAsText()).jsonArray
                assertEquals(
                    listOf("Fourth", "First", "Second", "Third"),
                    order.map { row -> row.jsonObject.text("name") },
                )
                assertEquals(
                    listOf(1, 2, 3, 4),
                    order.map { row -> row.jsonObject.number("position") },
                )
                assertEquals(
                    listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                    PromptTestSchema.orderedCategories(dataSource),
                )

                // The legacy backend answered a conflict here; an unknown id is a not-found.
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
                    listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                    PromptTestSchema.orderedCategories(dataSource),
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
            install(RequestValidation) { validatePromptRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            installPromptModule(Database.connect(datasource = dataSource))
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
        const val BASE_PATH = "/api/admin/prompts/categories"
        const val NOT_FOUND_MESSAGE = "Prompt category not found"
        const val NAME_CONFLICT_MESSAGE = "Prompt category name already exists"
    }
}

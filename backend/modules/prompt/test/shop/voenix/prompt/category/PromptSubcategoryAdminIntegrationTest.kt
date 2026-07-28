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
 * The real subcategory routes on real PostgreSQL. Positions count per category here, so the tests
 * describe the three writes that touch two sequences at once: a create appends in one category, a
 * category change appends in the target and compacts the source, and a delete compacts.
 */
internal class PromptSubcategoryAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends in its own category and answers with a flat category id`() {
        migratedDataSource("prompt-subcategory-create-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")

            adminApplication(dataSource, "prompt-subcategory-create-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val created =
                    admin.createSubcategory(
                        token,
                        """{"categoryId":1,"name":"  Kids  ","description":"  For children  "}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Kids", body.text("name"))
                assertEquals("For children", body.text("description"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("position"))
                assertEquals(
                    "/api/admin/prompts/subcategories/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSubcategory(token, """{"categoryId":1,"name":"Adults"}""").status,
                )
                // The second category starts at 1 again: positions count per category.
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSubcategory(token, """{"categoryId":2,"name":"Dogs"}""").status,
                )

                assertEquals(
                    listOf("Kids" to 1, "Adults" to 2),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Dogs" to 1),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 2),
                )

                // The list is ordered by the category order first, then by the own position.
                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf("Kids", "Adults", "Dogs"),
                    listed.map { row -> row.jsonObject.text("name") },
                )
            }
        }
    }

    @Test
    fun `an unknown category is a field error and not a conflict`() {
        migratedDataSource("prompt-subcategory-unknown-category-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits")

            adminApplication(dataSource, "prompt-subcategory-unknown-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val rejected =
                    admin.createSubcategory(token, """{"categoryId":404,"name":"Ghost"}""")
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                val body = Json.parseToJsonElement(rejected.bodyAsText()).jsonObject
                assertEquals("Validation failed", body.text("message"))
                assertEquals(
                    listOf("Prompt category does not exist"),
                    body.getValue("errors").jsonObject.getValue("categoryId").jsonArray.map {
                        message ->
                        message.jsonPrimitive.content
                    },
                )
            }
        }
    }

    @Test
    fun `a name that differs only in case is rejected per category`() {
        migratedDataSource("prompt-subcategory-duplicate-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids")

            adminApplication(dataSource, "prompt-subcategory-duplicate-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.createSubcategory(token, """{"categoryId":1,"name":" kIDS "}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )

                // The same name in another category is fine: the rule counts per category.
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSubcategory(token, """{"categoryId":2,"name":"KIDS"}""").status,
                )
            }
        }
    }

    @Test
    fun `a category change appends in the target and compacts the source`() {
        migratedDataSource("prompt-subcategory-move-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids", "Adults", "Pets")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 2, "Dogs")

            adminApplication(dataSource, "prompt-subcategory-move-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val moved =
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":2,"name":"Kids"}""",
                    )
                assertEquals(HttpStatusCode.OK, moved.status)
                val body = Json.parseToJsonElement(moved.bodyAsText()).jsonObject
                assertEquals(2, body.number("categoryId"))
                assertEquals(2, body.number("position"))

                assertEquals(
                    listOf("Adults" to 1, "Pets" to 2),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Dogs" to 1, "Kids" to 2),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 2),
                )
            }
        }
    }

    @Test
    fun `a subcategory prompts use cannot move to another category`() {
        migratedDataSource("prompt-subcategory-used-move-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids", "Adults")
            PromptTestSchema.seedPromptIn(dataSource, categoryId = 1, subcategoryId = 1)

            adminApplication(dataSource, "prompt-subcategory-used-move-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val rejected =
                    admin.updateSubcategory(
                        token,
                        id = 1,
                        body = """{"categoryId":2,"name":"Kids"}""",
                    )
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                assertEquals(
                    listOf(
                        "Prompt subcategory is used by prompts and cannot be moved to another category"
                    ),
                    Json.parseToJsonElement(rejected.bodyAsText())
                        .jsonObject
                        .getValue("errors")
                        .jsonObject
                        .getValue("categoryId")
                        .jsonArray
                        .map { message -> message.jsonPrimitive.content },
                )

                // Renaming inside the same category is still allowed.
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateSubcategory(
                            token,
                            id = 1,
                            body = """{"categoryId":1,"name":"Children"}""",
                        )
                        .status,
                )
                assertEquals(
                    listOf("Children" to 1, "Adults" to 2),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
            }
        }
    }

    @Test
    fun `delete compacts its category and is blocked while prompts use the subcategory`() {
        migratedDataSource("prompt-subcategory-delete-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids", "Adults", "Pets")
            PromptTestSchema.seedPromptIn(dataSource, categoryId = 1, subcategoryId = 3)

            adminApplication(dataSource, "prompt-subcategory-delete-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.delete("$BASE_PATH/3") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Prompt subcategory is used by prompts and cannot be deleted",
                )

                assertEquals(
                    HttpStatusCode.NoContent,
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }.status,
                )
                assertEquals(
                    listOf("Adults" to 1, "Pets" to 2),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
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
    fun `reorder works inside one category and refuses a target from another one`() {
        migratedDataSource("prompt-subcategory-reorder-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
            PromptTestSchema.seedSubcategories(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
            )
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 2, "Dogs")

            adminApplication(dataSource, "prompt-subcategory-reorder-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val reordered = admin.reorder(token, """{"sourceId":3,"targetId":1}""")
                assertEquals(HttpStatusCode.OK, reordered.status)
                val order = Json.parseToJsonElement(reordered.bodyAsText()).jsonArray
                // Only the affected category comes back, not every subcategory there is.
                assertEquals(
                    listOf("Third", "First", "Second"),
                    order.map { row -> row.jsonObject.text("name") },
                )
                assertEquals(
                    listOf(1, 2, 3),
                    order.map { row -> row.jsonObject.number("position") },
                )

                // A target in another category is outside the ordered list this move works on.
                assertApiMessage(
                    admin.reorder(token, """{"sourceId":1,"targetId":4}"""),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.reorder(token, """{"sourceId":404,"targetId":1}"""),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertEquals(
                    listOf("Third" to 1, "First" to 2, "Second" to 3),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
                )
                assertEquals(
                    listOf("Dogs" to 1),
                    PromptTestSchema.orderedSubcategories(dataSource, categoryId = 2),
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
        const val BASE_PATH = "/api/admin/prompts/subcategories"
        const val NOT_FOUND_MESSAGE = "Prompt subcategory not found"
        const val NAME_CONFLICT_MESSAGE =
            "Prompt subcategory name already exists in this prompt category"
    }
}

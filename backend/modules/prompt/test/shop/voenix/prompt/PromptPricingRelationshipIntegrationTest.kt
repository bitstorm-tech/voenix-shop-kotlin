package shop.voenix.prompt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The relationship between a prompt and the price row it owns, exercised across the two modules
 * that share it.
 *
 * The price is a row of the pricing module and a reference of the prompt module, so three things
 * have to hold at once and none of them is provable inside one module: the price a prompt minted is
 * a normal price to the pricing routes, an edit made there is what the prompt answers with
 * afterwards, and the row cannot be taken away from the prompt that holds it.
 *
 * The last one is asserted against the database rather than through a route, because the pricing
 * module deliberately exposes no delete: a price is deleted by the owner that holds it, inside the
 * owner's transaction. What the assertion proves is the same thing a delete route would have to
 * survive — the restriction is enforced by the schema, and the failure carries a SQL state instead
 * of a constraint name.
 */
internal class PromptPricingRelationshipIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a prompt price is a normal price to the pricing routes`() {
        migratedDataSource("prompt-pricing-relationship-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-pricing-relationship-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val promptId = admin.createPrompt(token)
                val priceId = PromptTestSchema.priceIdOf(dataSource, promptId)

                // The pricing routes read the row the prompt minted.
                val price = admin.get("/api/admin/prices/$priceId")
                assertEquals(HttpStatusCode.OK, price.status)
                assertEquals(
                    499,
                    Json.parseToJsonElement(price.bodyAsText())
                        .jsonObject
                        .number("salesTotalInputCents"),
                )

                // An edit made there is what the prompt answers with afterwards: the prompt does
                // not copy the amounts, it resolves them on every read.
                val updated =
                    admin.put("/api/admin/prices/$priceId") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":1200}"""
                        )
                    }
                assertEquals(HttpStatusCode.OK, updated.status)

                val prompt =
                    Json.parseToJsonElement(admin.get("$PROMPT_PATH/$promptId").bodyAsText())
                assertEquals(
                    1200,
                    prompt.jsonObject.getValue("price").jsonObject.number("salesTotalInputCents"),
                )

                // The list projection is recalculated from the same row.
                val row =
                    Json.parseToJsonElement(admin.get(PROMPT_PATH).bodyAsText())
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(1200, row.getValue("price").jsonObject.number("salesTotalGross"))
            }
        }
    }

    @Test
    fun `the price row a prompt holds cannot be deleted away from it`() {
        migratedDataSource("prompt-pricing-restrict-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-pricing-restrict-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val promptId = admin.createPrompt(token)
                val priceId = PromptTestSchema.priceIdOf(dataSource, promptId)

                dataSource.connection.use { connection ->
                    PromptTestSchema.assertSqlState(
                        "23503",
                        connection,
                        "DELETE FROM voenix.prices WHERE id = $priceId",
                    )
                }
                assertEquals(priceId, PromptTestSchema.priceIdOf(dataSource, promptId))
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        PromptTestSchema.reset(dataSource)
        PromptTestSchema.seedVat(dataSource)
        PromptTestSchema.seedCategories(dataSource, "Portraits")
    }

    /** Creates one prompt and returns its id. */
    private suspend fun HttpClient.createPrompt(token: String): Long {
        val created =
            post(PROMPT_PATH) {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"title":"Watercolor","promptText":"Turn the photo into art.",""" +
                        """"categoryId":1,"slotVariantIds":[],"active":true,""" +
                        """"price":{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499}}"""
                )
            }
        assertEquals(HttpStatusCode.Created, created.status)
        return Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id").toLong()
    }

    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) {
                validatePromptRequests()
                validatePricingRequests()
            }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            installPromptModule(
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

    private fun JsonObject.number(field: String): Int =
        getValue(field).jsonPrimitive.content.toInt()

    private companion object {
        const val PROMPT_PATH = "/api/admin/prompts"
    }
}

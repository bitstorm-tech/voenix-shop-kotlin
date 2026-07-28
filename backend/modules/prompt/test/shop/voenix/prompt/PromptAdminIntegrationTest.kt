package shop.voenix.prompt

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 * The prompt write slice against real Ktor routes and a real PostgreSQL database, including the
 * real pricing module — which is the point of most of these tests: a prompt and its price are one
 * transaction, so both failure directions have to be proven, not assumed.
 *
 * The other half is the four references a prompt has. Each of them is answered as a field error of
 * the field that named it, and telling them apart is what the per-statement mapping of this slice
 * exists for, so each of them is asserted separately.
 */
internal class PromptAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends, deduplicates its variants, and keeps the prompt text verbatim`() {
        migratedDataSource("prompt-create-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-create-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val created = admin.createPrompt(token, promptBody())
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals(1, body.number("position"))
                // Title and llm come back trimmed, the prompt text does not.
                assertEquals("Watercolor portrait", body.text("title"))
                assertEquals("gpt-image-1", body.text("llm"))
                assertEquals(UNTRIMMED_PROMPT_TEXT, body.text("promptText"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("subcategoryId"))
                // [12, 9, 12] in, [9, 12] out.
                assertEquals(
                    listOf(9, 12),
                    body.getValue("slotVariantIds").jsonArray.map { id ->
                        id.jsonPrimitive.content.toInt()
                    },
                )
                assertEquals(
                    "/api/admin/prompts/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                val id = body.number("id").toLong()
                assertEquals(UNTRIMMED_PROMPT_TEXT, PromptTestSchema.promptTextOf(dataSource, id))
                assertEquals(listOf(9L, 12L), PromptTestSchema.mappedSlotVariantIds(dataSource, id))

                // The price was minted by this write, and the prompt carries no separate price id.
                assertEquals(
                    PromptTestSchema.storedPriceIds(dataSource),
                    listOf(body.getValue("price").jsonObject.number("id").toLong()),
                )
                assertEquals(499, body.getValue("price").jsonObject.number("salesTotalInputCents"))
                assertNull(body["priceId"])

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createPrompt(token, promptBody(title = "Second prompt")).status,
                )
                assertEquals(
                    listOf("Watercolor portrait" to 1, "Second prompt" to 2),
                    PromptTestSchema.orderedPrompts(dataSource),
                )
            }
        }
    }

    @Test
    fun `a rejected price leaves no prompt and a rejected prompt leaves no price`() {
        migratedDataSource("prompt-atomicity-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-atomicity-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                // The price is prepared before the transaction opens, so a rejected one never
                // reaches the prompt.
                assertFieldError(
                    admin.createPrompt(token, promptBody(salesVatId = 404)),
                    "price.salesVatId",
                    "Sales VAT not found",
                )
                assertEquals(emptyList(), PromptTestSchema.orderedPrompts(dataSource))
                assertEquals(emptyList(), PromptTestSchema.storedPriceIds(dataSource))

                // The other direction: the price row is written first, inside the transaction the
                // prompt then fails in. Nothing may survive that rollback.
                assertFieldError(
                    admin.createPrompt(token, promptBody(slotVariantIds = listOf(404))),
                    "slotVariantIds",
                    "One or more prompt slot variants do not exist",
                )
                assertEquals(emptyList(), PromptTestSchema.orderedPrompts(dataSource))
                assertEquals(emptyList(), PromptTestSchema.storedPriceIds(dataSource))

                // And the same write succeeds once both are right.
                assertEquals(HttpStatusCode.Created, admin.createPrompt(token, promptBody()).status)
                assertEquals(1, PromptTestSchema.storedPriceIds(dataSource).size)
            }
        }
    }

    /**
     * The three references a prompt statement can fail on are reported apart from each other, and
     * none of them is a conflict: they are all values the client submitted.
     */
    @Test
    fun `each wrong reference is its own field error and never a conflict`() {
        migratedDataSource("prompt-references-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-references-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createPrompt(token, promptBody(categoryId = 404)),
                    "categoryId",
                    "Prompt category does not exist",
                )
                assertFieldError(
                    admin.createPrompt(token, promptBody(subcategoryId = 404)),
                    "subcategoryId",
                    "Prompt subcategory does not exist in this prompt category",
                )
                // A subcategory that exists but in another category is the same answer: the
                // composite key rejects the pair, not the row.
                assertFieldError(
                    admin.createPrompt(token, promptBody(categoryId = 2, subcategoryId = 1)),
                    "subcategoryId",
                    "Prompt subcategory does not exist in this prompt category",
                )
                assertFieldError(
                    admin.createPrompt(token, promptBody(slotVariantIds = listOf(9, 404))),
                    "slotVariantIds",
                    "One or more prompt slot variants do not exist",
                )
                assertEquals(emptyList(), PromptTestSchema.orderedPrompts(dataSource))
                assertEquals(emptyList(), PromptTestSchema.storedPriceIds(dataSource))
            }
        }
    }

    @Test
    fun `update replaces the whole variant set and writes over the same price row`() {
        migratedDataSource("prompt-update-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-update-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val created = admin.createPrompt(token, promptBody())
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                val priceId = PromptTestSchema.storedPriceIds(dataSource).single()

                val updated =
                    admin.updatePrompt(
                        token,
                        id.toLong(),
                        promptBody(
                            title = "Renamed",
                            subcategoryId = 2,
                            slotVariantIds = listOf(3),
                            salesTotalInputCents = 999,
                        ),
                    )
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Renamed", body.text("title"))
                assertEquals(2, body.number("subcategoryId"))
                // The mapping set is replaced, not merged.
                assertEquals(
                    listOf(3),
                    body.getValue("slotVariantIds").jsonArray.map { value ->
                        value.jsonPrimitive.content.toInt()
                    },
                )
                assertEquals(
                    listOf(3L),
                    PromptTestSchema.mappedSlotVariantIds(dataSource, id.toLong()),
                )
                // The price keeps its id and its row: the prompt never has to rewrite a reference.
                assertEquals(priceId, body.getValue("price").jsonObject.number("id").toLong())
                assertEquals(999, body.getValue("price").jsonObject.number("salesTotalInputCents"))
                assertEquals(listOf(priceId), PromptTestSchema.storedPriceIds(dataSource))
                // The position is not touched by an update.
                assertEquals(1, body.number("position"))

                assertApiMessage(
                    admin.updatePrompt(token, id = 404, body = promptBody()),
                    HttpStatusCode.NotFound,
                    "Prompt not found",
                )
            }
        }
    }

    /**
     * The nullable `price_id` column permits a prompt without a price row. A valid update repairs
     * that state instead of failing on it, while an update that submits no price stays a rejected
     * request — a prompt is something the shop sells.
     */
    @Test
    fun `an update repairs a prompt whose price row was never linked`() {
        migratedDataSource("prompt-null-price-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-null-price-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val created = admin.createPrompt(token, promptBody())
                val id =
                    Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id").toLong()

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET price_id = NULL WHERE id = $id",
                )
                PromptTestSchema.execute(dataSource, "DELETE FROM voenix.prices")
                assertNull(PromptTestSchema.priceIdOf(dataSource, id))
                val withoutPrice = admin.get("$BASE_PATH/$id")
                assertEquals(HttpStatusCode.OK, withoutPrice.status)
                assertNull(
                    Json.parseToJsonElement(withoutPrice.bodyAsText())
                        .jsonObject["price"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                )

                // A missing request price is still a rejected request.
                assertFieldError(
                    admin.updatePrompt(token, id, promptBody(withPrice = false)),
                    "price",
                    "Price is required",
                )
                assertNull(PromptTestSchema.priceIdOf(dataSource, id))

                val repaired = admin.updatePrompt(token, id, promptBody(salesTotalInputCents = 750))
                assertEquals(HttpStatusCode.OK, repaired.status)
                val price =
                    Json.parseToJsonElement(repaired.bodyAsText())
                        .jsonObject
                        .getValue("price")
                        .jsonObject
                assertEquals(750, price.number("salesTotalInputCents"))
                assertEquals(
                    PromptTestSchema.priceIdOf(dataSource, id),
                    price.number("id").toLong(),
                )
                assertEquals(1, PromptTestSchema.storedPriceIds(dataSource).size)
            }
        }
    }

    @Test
    fun `the list answers flat rows with names and the small price projection`() {
        migratedDataSource("prompt-list-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-list-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                admin.createPrompt(token, promptBody())
                admin.createPrompt(
                    token,
                    promptBody(title = "Dogs", categoryId = 2, subcategoryId = null),
                )

                val listed = admin.get(BASE_PATH)
                assertEquals(HttpStatusCode.OK, listed.status)
                val rows = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
                assertEquals(
                    listOf("Watercolor portrait", "Dogs"),
                    rows.map { it.jsonObject.text("title") },
                )
                val first = rows.first().jsonObject
                assertEquals("Portraits", first.text("categoryName"))
                assertEquals("Kids", first.text("subcategoryName"))
                assertEquals(
                    setOf(
                        "salesTotalNet",
                        "salesTotalGross",
                        "salesTotalTax",
                        "salesVatRatePercent",
                    ),
                    first.getValue("price").jsonObject.keys,
                )
                assertEquals(499, first.getValue("price").jsonObject.number("salesTotalGross"))
                assertEquals(19, first.getValue("price").jsonObject.number("salesVatRatePercent"))
                // The second prompt has no subcategory, and that stays a null name.
                assertNull(rows[1].jsonObject["subcategoryName"]?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    @Test
    fun `a submitted price id is never honored`() {
        migratedDataSource("prompt-price-id-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-price-id-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                admin.createPrompt(token, promptBody())
                val foreignPriceId = PromptTestSchema.storedPriceIds(dataSource).single()

                val created =
                    admin.createPrompt(
                        token,
                        promptBody(title = "Second prompt", priceId = foreignPriceId),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                assertEquals(2, PromptTestSchema.storedPriceIds(dataSource).size)
                assertTrue(
                    PromptTestSchema.priceIdOf(dataSource, id.toLong()) != foreignPriceId,
                    "A submitted price id must not be stored",
                )
            }
        }
    }

    /** Categories, subcategories, slot variants, and the VAT entry every price refers to. */
    private fun seedCatalog(dataSource: DataSource) {
        PromptTestSchema.reset(dataSource)
        PromptTestSchema.seedVat(dataSource)
        PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
        PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Kids", "Adults")
        PromptTestSchema.seedSlots(dataSource, "Style")
        PromptTestSchema.seedVariants(
            dataSource,
            slotId = 1,
            *Array(SEEDED_VARIANTS) { index -> "Variant ${index + 1}" },
        )
    }

    @Suppress("LongParameterList")
    private fun promptBody(
        title: String = "  Watercolor portrait  ",
        categoryId: Long? = 1,
        subcategoryId: Long? = 1,
        slotVariantIds: List<Long> = listOf(12, 9, 12),
        withPrice: Boolean = true,
        salesVatId: Long = 1,
        salesTotalInputCents: Int = 499,
        priceId: Long? = null,
    ): String {
        val price =
            if (withPrice) {
                ""","price":{"purchaseVatId":1,"salesVatId":$salesVatId,""" +
                    """"salesTotalInputCents":$salesTotalInputCents}"""
            } else {
                ""
            }
        return """{"title":"$title","promptText":"$JSON_PROMPT_TEXT",""" +
            """"categoryId":${categoryId ?: "null"},"subcategoryId":${subcategoryId ?: "null"},""" +
            """"slotVariantIds":${slotVariantIds.joinToString(",", "[", "]")},""" +
            """"llm":"  gpt-image-1  ","active":true,"archived":false""" +
            (priceId?.let { value -> ""","priceId":$value""" } ?: "") +
            price +
            "}"
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
            val database = Database.connect(datasource = dataSource)
            installPromptModule(
                database,
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
        const val SEEDED_VARIANTS = 12

        /** The stored text with the whitespace the author typed, and the same text as JSON. */
        const val UNTRIMMED_PROMPT_TEXT = "\n  Turn the photo into a watercolor portrait.  \n"
        const val JSON_PROMPT_TEXT = "\\n  Turn the photo into a watercolor portrait.  \\n"
    }
}

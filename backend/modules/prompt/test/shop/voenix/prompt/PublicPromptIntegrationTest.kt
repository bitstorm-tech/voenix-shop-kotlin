package shop.voenix.prompt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
 * The storefront half of the prompt slice against real Ktor routes and a real PostgreSQL database.
 *
 * Every cell of the visibility matrix is written through the **admin** routes and then read by an
 * anonymous client, so what is asserted is the answer a customer's browser gets from the state an
 * admin can actually produce. The flags are flipped with SQL afterwards because that is the one
 * thing the admin routes cannot do in a single step for a category, a subcategory, and a prompt at
 * once — the values themselves are ordinary ones the admin routes write too.
 *
 * The response body is compared as a **whole** JSON document, because that is what catches a field
 * the public contract must not have. One of them is not a detail: `promptText` is the product this
 * shop sells, and an anonymous client that receives it does not have to buy anything.
 */
internal class PublicPromptIntegrationTest : PostgresIntegrationTest() {
    /**
     * The filter matrix. Six prompts, one per combination the storefront rule distinguishes, and
     * only the two visible ones come back — followed by the three reactivations that bring the
     * others back one at a time.
     */
    @Test
    fun `the public list shows only prompts whose whole category path is active`() {
        migratedDataSource("prompt-public-filter-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-filter-session-secret") { fixture ->
                // 1: active, active category, no subcategory.
                fixture.createPrompt(promptBody("Without a subcategory", categoryId = 1))
                // 2: active, active category, active subcategory.
                fixture.createPrompt(
                    promptBody("In an active subcategory", categoryId = 1, subcategoryId = 1)
                )
                // 3: active, active category, subcategory switched off below.
                fixture.createPrompt(
                    promptBody("In an inactive subcategory", categoryId = 1, subcategoryId = 2)
                )
                // 4: active, category switched off below.
                fixture.createPrompt(promptBody("In an inactive category", categoryId = 2))
                // 5: complete but deactivated below.
                fixture.createPrompt(promptBody("Deactivated", categoryId = 1, subcategoryId = 1))
                // 6: complete but archived below — the module's soft delete.
                fixture.createPrompt(promptBody("Archived", categoryId = 1))

                PromptTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.prompt_subcategories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.prompt_categories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.prompts SET active = FALSE WHERE id = 5;
                    UPDATE voenix.prompts SET archived = TRUE WHERE id = 6;
                    """
                        .trimIndent(),
                )

                assertEquals(listOf(1L, 2L), fixture.listedIds())

                // Switching the subcategory back on makes exactly its prompt visible again.
                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompt_subcategories SET active = TRUE WHERE id = 2",
                )
                assertEquals(listOf(1L, 2L, 3L), fixture.listedIds())

                // And so does switching the category back on.
                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompt_categories SET active = TRUE WHERE id = 2",
                )
                assertEquals(listOf(1L, 2L, 3L, 4L), fixture.listedIds())

                // Un-archiving is the third reactivation, and it is a separate flag from `active`:
                // the prompt that is only deactivated stays hidden while it comes back.
                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET archived = FALSE WHERE id = 6",
                )
                assertEquals(listOf(1L, 2L, 3L, 4L, 6L), fixture.listedIds())

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET active = TRUE WHERE id = 5",
                )
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), fixture.listedIds())
            }
        }
    }

    /**
     * The approved deviation, proved by swapping two positions: the filtered list is the same
     * global order as the unfiltered one, not a second order of its own. Legacy sorted the filtered
     * list by subcategory and title, which meant the order an admin arranged stopped applying the
     * moment a customer picked a category.
     */
    @Test
    fun `the order is position and id with and without the category filter`() {
        migratedDataSource("prompt-public-order-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-order-session-secret") { fixture ->
                // Titles that sort the other way round, so a list sorted by title would be visible.
                fixture.createPrompt(promptBody("Zebra", categoryId = 1, subcategoryId = 2))
                fixture.createPrompt(promptBody("Yak", categoryId = 2))
                fixture.createPrompt(promptBody("Ant", categoryId = 1, subcategoryId = 1))
                fixture.createPrompt(promptBody("Bee", categoryId = 2))

                assertEquals(listOf(1L, 2L, 3L, 4L), fixture.listedIds())
                assertEquals(listOf(1L, 3L), fixture.listedIds(categoryId = 1))
                assertEquals(listOf(2L, 4L), fixture.listedIds(categoryId = 2))

                // Swapping two positions in one statement — the unique rule is deferred to COMMIT —
                // turns both lists around in the same way.
                PromptTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.prompts
                    SET position = CASE id WHEN 1 THEN 3 ELSE 1 END
                    WHERE id IN (1, 3)
                    """
                        .trimIndent(),
                )

                assertEquals(listOf(3L, 2L, 1L, 4L), fixture.listedIds())
                assertEquals(listOf(3L, 1L), fixture.listedIds(categoryId = 1))
                assertEquals(listOf(1 to 3L, 3 to 1L), fixture.listedPositions(categoryId = 1))
            }
        }
    }

    /**
     * The whole document, and then the rule that document exists for: no field of the admin
     * contract leaks into the anonymous answer — least of all `promptText`, which is what the shop
     * sells.
     */
    @Test
    fun `the public payload is the documented shape and never carries the prompt text`() {
        migratedDataSource("prompt-public-shape-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-shape-session-secret") { fixture ->
                fixture.images.put(FIRST_IMAGE)
                fixture.createPrompt(
                    promptBody(
                        "Watercolor portrait",
                        categoryId = 1,
                        subcategoryId = 2,
                        exampleImageFilename = FIRST_IMAGE,
                    )
                )
                fixture.createPrompt(promptBody("Oil painting", categoryId = 2, llm = null))

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_PUBLIC_LIST),
                    Json.parseToJsonElement(fixture.list().bodyAsText()),
                )

                val prompt = fixture.listedItems().first()
                FORBIDDEN_FIELDS.forEach { field ->
                    assertTrue(field !in prompt.keys, "The public prompt must not carry `$field`")
                }
                // The text is stored and real: it is left out of the answer, not missing from the
                // prompt.
                assertEquals(
                    PROMPT_TEXT,
                    PromptTestSchema.promptTextOf(dataSource, promptId = 1),
                )
                assertTrue(
                    PROMPT_TEXT !in fixture.list().bodyAsText(),
                    "The prompt text must not appear anywhere in the public response",
                )
            }
        }
    }

    /**
     * The list may not read anything per prompt. Three prompts must cost the same statements as
     * one, and every price of the page must be resolved by exactly one batched lookup.
     */
    @Test
    fun `the public list runs the same statements for one prompt and for three`() {
        migratedDataSource("prompt-public-statements-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            storefrontApplication(counting, "prompt-public-statements-session-secret") { fixture ->
                fixture.createPrompt(promptBody("First", categoryId = 1, subcategoryId = 1))
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(1, fixture.listedIds().size)
                val forOnePrompt = counting.normalizedStatements()
                assertEquals(listOf(setOf(1L)), fixture.prices.requestedIds.toList())

                fixture.createPrompt(promptBody("Second", categoryId = 1))
                fixture.createPrompt(promptBody("Third", categoryId = 2))
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(3, fixture.listedIds().size)
                val forThreePrompts = counting.normalizedStatements()

                assertEquals(
                    forOnePrompt,
                    forThreePrompts,
                    "The public list must run the same statements regardless of how many prompts " +
                        "it answers",
                )
                assertEquals(
                    PUBLIC_LIST_STATEMENT_COUNT,
                    forOnePrompt.size,
                    "Statements: $forOnePrompt",
                )
                // One lookup for three prices, carrying the three distinct ids.
                assertEquals(listOf(setOf(1L, 2L, 3L)), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * An unknown category is a question with the answer `[]`, not an error — and an answer without
     * a single row asks the pricing module nothing at all.
     */
    @Test
    fun `an empty answer is a bare empty array without a price lookup`() {
        migratedDataSource("prompt-public-empty-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-empty-session-secret") { fixture ->
                assertEquals("[]", fixture.list().bodyAsText())

                fixture.createPrompt(promptBody("Only prompt", categoryId = 1))
                fixture.prices.requestedIds.clear()

                val unknownCategory = fixture.list(categoryId = 404)
                assertEquals(HttpStatusCode.OK, unknownCategory.status)
                assertEquals("[]", unknownCategory.bodyAsText())
                // The empty category never reached the pricing module; the populated one did.
                assertEquals(emptyList<Set<Long>>(), fixture.prices.requestedIds.toList())
                assertEquals(listOf(1L), fixture.listedIds(categoryId = 1))
                assertEquals(listOf(setOf(1L)), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * The price is recalculated from the current VAT entry on every read, never read back from what
     * the write once stored. Raising the VAT therefore changes what the storefront shows without
     * anybody touching the prompt.
     */
    @Test
    fun `a changed vat rate is recalculated into the public price projection`() {
        migratedDataSource("prompt-public-vat-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-vat-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Watercolor", categoryId = 1))

                assertEquals(
                    Json.parseToJsonElement(
                        """{"salesTotalNet":419,"salesTotalGross":499,""" +
                            """"salesTotalTax":80,"regularSalesTotalGross":null,""" +
                            """"salesVatRatePercent":19}"""
                    ),
                    fixture.listedItems().single().getValue("price"),
                )

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.value_added_taxes SET percent = 7 WHERE id = 1",
                )

                // The gross total is what the admin entered and stays; the split moves with the
                // VAT.
                assertEquals(
                    Json.parseToJsonElement(
                        """{"salesTotalNet":466,"salesTotalGross":499,""" +
                            """"salesTotalTax":33,"regularSalesTotalGross":null,""" +
                            """"salesVatRatePercent":7}"""
                    ),
                    fixture.listedItems().single().getValue("price"),
                )
            }
        }
    }

    /**
     * The column is nullable, so a prompt without a price row is a state the database permits. The
     * storefront then answers `null` — never `0`, which is a price a shop may legitimately charge.
     */
    @Test
    fun `a prompt without a price row answers a null price and not a zero`() {
        migratedDataSource("prompt-public-null-price-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-null-price-session-secret") { fixture
                ->
                fixture.createPrompt(promptBody("With a price", categoryId = 1))
                fixture.createPrompt(promptBody("Without a price", categoryId = 1))

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET price_id = NULL WHERE id = 2",
                )
                fixture.prices.requestedIds.clear()

                val prompts = fixture.listedItems()
                assertEquals(499, prompts[0].getValue("price").jsonObject.number("salesTotalGross"))
                assertEquals(JsonNull, prompts[1].getValue("price"))
                // The one price that is still linked is the only one the lookup asked for.
                assertEquals(listOf(setOf(1L)), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * A discount is configured on the price, and the storefront answers both amounts:
     * `salesTotalGross` is what the customer pays, `regularSalesTotalGross` is the amount a shop
     * strikes through. A prompt without a discount still carries the key — it answers `null`, so a
     * client never has to ask whether the field exists.
     */
    @Test
    fun `a discounted prompt answers the effective total next to the regular one`() {
        migratedDataSource("prompt-public-discount-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "prompt-public-discount-session-secret") { fixture ->
                fixture.createPrompt(
                    promptBody("On sale", categoryId = 1, price = DISCOUNTED_PRICE)
                )
                fixture.createPrompt(promptBody("Full price", categoryId = 1))

                val (discounted, regular) = fixture.listedItems().map { it.getValue("price") }
                assertEquals(399, discounted.jsonObject.number("salesTotalGross"))
                assertEquals(499, discounted.jsonObject.number("regularSalesTotalGross"))
                assertEquals(499, regular.jsonObject.number("salesTotalGross"))
                assertEquals(JsonNull, regular.jsonObject.getValue("regularSalesTotalGross"))
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
        PromptTestSchema.seedVariants(dataSource, slotId = 1, "Watercolor", "Oil")
    }

    private fun promptBody(
        title: String,
        categoryId: Long,
        subcategoryId: Long? = null,
        exampleImageFilename: String? = null,
        llm: String? = "gpt-image-1",
        price: String = REGULAR_PRICE,
    ): String =
        """{"title":"$title","promptText":"$PROMPT_TEXT","categoryId":$categoryId,""" +
            """"subcategoryId":${subcategoryId ?: "null"},"slotVariantIds":[1],""" +
            """"exampleImageFilename":${exampleImageFilename?.let { "\"$it\"" } ?: "null"},""" +
            """"llm":${llm?.let { "\"$it\"" } ?: "null"},"active":true,"archived":false,""" +
            """"price":$price}"""

    /**
     * Runs [block] against the real module installed on [dataSource], with an admin client that
     * writes the catalog and an anonymous client that reads it.
     */
    private fun storefrontApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (PublicFixture) -> Unit,
    ) = testApplication {
        val images = RecordingPublicImageStorage()
        lateinit var prices: CountingPriceCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validatePromptRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            installPromptModule(database, images, prices)
            routing {
                post("/test/sign-in") {
                    call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val admin = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
        val token =
            Json.parseToJsonElement(admin.get("/api/antiforgery/token").bodyAsText())
                .jsonObject
                .getValue("requestToken")
                .jsonPrimitive
                .content
        // The storefront client has no cookie jar, so it never carries the admin session.
        block(PublicFixture(admin, token, client, images, prices))
    }

    /** The two clients a storefront test drives, plus the doubles it counts calls on. */
    private class PublicFixture(
        val admin: HttpClient,
        val token: String,
        val anonymous: HttpClient,
        val images: RecordingPublicImageStorage,
        val prices: CountingPriceCatalog,
    ) {
        suspend fun createPrompt(body: String) {
            val created =
                admin.post("/api/admin/prompts") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }

        suspend fun list(categoryId: Long? = null): HttpResponse =
            anonymous.get(
                when (categoryId) {
                    null -> PUBLIC_PATH
                    else -> "$PUBLIC_PATH?categoryId=$categoryId"
                }
            )

        suspend fun listedItems(categoryId: Long? = null): List<JsonObject> =
            Json.parseToJsonElement(list(categoryId).bodyAsText()).jsonArray.map { item ->
                item.jsonObject
            }

        suspend fun listedIds(categoryId: Long? = null): List<Long> =
            listedItems(categoryId).map { item -> item.getValue("id").jsonPrimitive.long }

        suspend fun listedPositions(categoryId: Long? = null): List<Pair<Int, Long>> =
            listedItems(categoryId).map { item ->
                item.getValue("position").jsonPrimitive.content.toInt() to
                    item.getValue("id").jsonPrimitive.long
            }
    }

    private fun JsonObject.number(field: String): Int =
        getValue(field).jsonPrimitive.content.toInt()

    private companion object {
        const val PUBLIC_PATH = "/api/prompts"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val PROMPT_TEXT = "Turn the photo into a watercolor portrait."

        /** A price without a discount: what the admin enters is what the customer pays. */
        const val REGULAR_PRICE =
            """{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499}"""

        /** 4,99 € with 20 % off, so the customer pays 3,99 €. */
        const val DISCOUNTED_PRICE =
            """{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499,""" +
                """"discountType":"PERCENTAGE","discountValue":20}"""

        /**
         * One statement of this module — the visible prompts with their two category levels — plus
         * the two the one batched `PriceCatalog.find` runs for the prices and their VAT entries.
         */
        const val PUBLIC_LIST_STATEMENT_COUNT = 3

        /** Every field of the admin contract that must never reach an anonymous client. */
        val FORBIDDEN_FIELDS =
            listOf(
                "promptText",
                "active",
                "archived",
                "priceId",
                "categoryId",
                "categoryName",
                "subcategoryId",
                "subcategoryName",
                "slotVariantIds",
            )

        val DOCUMENTED_PUBLIC_LIST =
            """
            [
              {
                "id": 1,
                "position": 1,
                "title": "Watercolor portrait",
                "category": { "id": 1, "name": "Portraits", "position": 1 },
                "subcategory": { "id": 2, "name": "Adults", "position": 2 },
                "exampleImageFilename": "$FIRST_IMAGE",
                "llm": "gpt-image-1",
                "price": {
                  "salesTotalNet": 419,
                  "salesTotalGross": 499,
                  "salesTotalTax": 80,
                  "regularSalesTotalGross": null,
                  "salesVatRatePercent": 19
                }
              },
              {
                "id": 2,
                "position": 2,
                "title": "Oil painting",
                "category": { "id": 2, "name": "Animals", "position": 2 },
                "subcategory": null,
                "exampleImageFilename": null,
                "llm": null,
                "price": {
                  "salesTotalNet": 419,
                  "salesTotalGross": 499,
                  "salesTotalTax": 80,
                  "regularSalesTotalGross": null,
                  "salesVatRatePercent": 19
                }
              }
            ]
            """
                .trimIndent()
    }
}

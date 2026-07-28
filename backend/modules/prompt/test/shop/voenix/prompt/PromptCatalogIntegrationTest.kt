package shop.voenix.prompt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * The exported [PromptCatalog] against the real module and a real PostgreSQL database.
 *
 * Every prompt the capability resolves is written through the admin routes, so the answers describe
 * prompts an admin can really produce. The two states an admin route cannot reach in one step — a
 * blank prompt text, which the input validation refuses, and a prompt whose `price_id` was never
 * linked, which only the nullable column permits — are produced with SQL afterwards.
 *
 * The test's centre of gravity is the deliberate divergence D12: these two lookups check `active &&
 * !archived` and nothing else, while the storefront list additionally checks both category levels.
 * A prompt in a deactivated category therefore stays generatable and buyable by id, and that is
 * asserted here rather than assumed.
 */
internal class PromptCatalogIntegrationTest : PostgresIntegrationTest() {
    /**
     * The composition rule in one assertion: the prompt's own text first, then every variant text
     * ordered `(slot.position, slot.id, variant.name, variant.id)`, joined by a blank line, with
     * every part trimmed and blank parts dropped.
     *
     * The slots are seeded so that their id order is the **reverse** of their position order, which
     * is what makes "ordered by position" provable instead of accidentally true. The variant-id
     * tiebreak has no fixture of its own: variant names are globally unique, so two variants of one
     * slot can never share a name.
     */
    @Test
    fun `composedText joins the prompt text and its variant texts in slot order`() {
        migratedDataSource("prompt-catalog-composition-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "prompt-catalog-composition-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Watercolor portrait", variantIds = ALL_VARIANTS))
                fixture.createPrompt(promptBody("Without variants", variantIds = emptyList()))

                assertEquals(
                    listOf(
                            TRIMMED_TEXT,
                            // Slot 2 has position 1, so its variants come first — by name.
                            "on a beach",
                            "in a meadow",
                            // Slot 1 has position 2; its blank variant contributes nothing.
                            "in watercolor",
                        )
                        .joinToString("\n\n"),
                    fixture.catalog.composedText(1),
                )

                // A prompt without a single slot variant composes to its own text.
                assertEquals(TRIMMED_TEXT, fixture.catalog.composedText(2))

                // The stored text is still the untrimmed one the author typed: the trim happens
                // while reading, which is the whole reason the write never trims.
                assertEquals(UNTRIMMED_TEXT, PromptTestSchema.promptTextOf(dataSource, 1))
            }
        }
    }

    /**
     * The four reasons there is no composed text are one answer, because a caller can do exactly
     * one thing about any of them. Each is proved against a prompt that resolved a moment earlier.
     */
    @Test
    fun `composedText answers null for an unknown, inactive, archived, or textless prompt`() {
        migratedDataSource("prompt-catalog-absent-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "prompt-catalog-absent-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Deactivated"))
                fixture.createPrompt(promptBody("Archived"))
                fixture.createPrompt(promptBody("Emptied"))

                assertEquals(TRIMMED_TEXT, fixture.catalog.composedText(1))
                assertEquals(TRIMMED_TEXT, fixture.catalog.composedText(2))
                assertEquals(TRIMMED_TEXT, fixture.catalog.composedText(3))
                assertNull(fixture.catalog.composedText(404), "An unknown id has no text")

                PromptTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.prompts SET active = FALSE WHERE id = 1;
                    UPDATE voenix.prompts SET archived = TRUE WHERE id = 2;
                    UPDATE voenix.prompts SET prompt_text = '   ' WHERE id = 3;
                    """
                        .trimIndent(),
                )

                assertNull(fixture.catalog.composedText(1), "An inactive prompt has no text")
                assertNull(fixture.catalog.composedText(2), "An archived prompt has no text")
                assertNull(fixture.catalog.composedText(3), "A blank text is no text")
            }
        }
    }

    /**
     * D12, the conscious divergence: a prompt whose category *and* subcategory are switched off
     * disappears from the storefront but stays generatable and buyable by id. Archiving it is what
     * ends both — that is this module's soft delete, and it is the only flag these lookups share
     * with the storefront besides `active`.
     */
    @Test
    fun `a prompt in a deactivated category still resolves while an archived one does not`() {
        migratedDataSource("prompt-catalog-divergence-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "prompt-catalog-divergence-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Hidden but usable", subcategoryId = 1))

                PromptTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.prompt_categories SET active = FALSE WHERE id = 1;
                    UPDATE voenix.prompt_subcategories SET active = FALSE WHERE id = 1;
                    """
                        .trimIndent(),
                )

                assertEquals(
                    TRIMMED_TEXT,
                    fixture.catalog.composedText(1),
                    "A deactivated category must not take the generation text away",
                )
                assertEquals(
                    mapOf(1L to 499),
                    fixture.catalog.findSalesGrossPriceCents(setOf(1L)),
                    "A deactivated category must not make a prompt unbuyable",
                )

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET archived = TRUE WHERE id = 1",
                )

                assertNull(fixture.catalog.composedText(1))
                assertEquals(emptyMap(), fixture.catalog.findSalesGrossPriceCents(setOf(1L)))
            }
        }
    }

    /**
     * Eligibility and the no-sentinel rule in one batch. The prompt priced at `0` is the point of
     * the rule: it is present with the value `0`, while every id that cannot be bought is absent —
     * so a caller can tell "free" from "not for sale" without asking a second question.
     */
    @Test
    fun `findSalesGrossPriceCents answers only usable prompts and never a zero sentinel`() {
        migratedDataSource("prompt-catalog-eligibility-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "prompt-catalog-eligibility-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Buyable"))
                fixture.createPrompt(promptBody("Free", grossCents = 0))
                fixture.createPrompt(promptBody("Deactivated"))
                fixture.createPrompt(promptBody("Archived"))
                fixture.createPrompt(promptBody("Without a price row"))

                PromptTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.prompts SET active = FALSE WHERE id = 3;
                    UPDATE voenix.prompts SET archived = TRUE WHERE id = 4;
                    UPDATE voenix.prompts SET price_id = NULL WHERE id = 5;
                    """
                        .trimIndent(),
                )

                assertEquals(
                    mapOf(1L to 499, 2L to 0),
                    fixture.catalog.findSalesGrossPriceCents(setOf(1L, 2L, 3L, 4L, 5L, 404L)),
                )
            }
        }
    }

    /**
     * Whatever the batch holds, the prices behind it are resolved in exactly one lookup — and a
     * batch with nothing to resolve asks the pricing module nothing at all. An empty set does not
     * even reach the database.
     */
    @Test
    fun `findSalesGrossPriceCents resolves a whole batch in one lookup`() {
        migratedDataSource("prompt-catalog-batching-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            catalogApplication(counting, "prompt-catalog-batching-session-secret") { fixture ->
                fixture.createPrompt(promptBody("First"))
                fixture.createPrompt(promptBody("Second"))
                fixture.createPrompt(promptBody("Third"))
                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET archived = TRUE WHERE id = 3",
                )
                counting.statements.clear()
                fixture.prices.requestedIds.clear()

                assertEquals(
                    mapOf(1L to 499, 2L to 499),
                    fixture.catalog.findSalesGrossPriceCents(setOf(1L, 2L, 3L)),
                )
                // One call, carrying the two price ids of the usable prompts only.
                assertEquals(listOf(setOf(1L, 2L)), fixture.prices.requestedIds.toList())
                // One statement of this module plus the two the batched lookup runs for the prices
                // and their VAT entries.
                assertEquals(3, counting.statements.size, "Statements: ${counting.statements}")

                // A batch of ids that resolve no usable prompt costs the one read and nothing else.
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(emptyMap(), fixture.catalog.findSalesGrossPriceCents(setOf(3L, 404L)))
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())
                assertEquals(1, counting.statements.size, "Statements: ${counting.statements}")

                // An empty set is answered without touching anything.
                counting.statements.clear()
                assertEquals(emptyMap(), fixture.catalog.findSalesGrossPriceCents(emptySet()))
                assertEquals(emptyList(), counting.statements.toList())
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * The amount is recalculated from the current VAT entry on every call, never read back from
     * what the write once stored. The prompt is priced net, so raising the VAT moves the gross
     * amount a cart would snapshot — which is exactly why a cart must ask again instead of caching.
     */
    @Test
    fun `a changed vat rate is recalculated into the gross cents`() {
        migratedDataSource("prompt-catalog-vat-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "prompt-catalog-vat-session-secret") { fixture ->
                fixture.createPrompt(promptBody("Priced net", netCents = 400))

                assertEquals(
                    mapOf(1L to 476),
                    fixture.catalog.findSalesGrossPriceCents(setOf(1L)),
                    "400 net at 19 percent is 476 gross",
                )

                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.value_added_taxes SET percent = 7 WHERE id = 1",
                )

                assertEquals(
                    mapOf(1L to 428),
                    fixture.catalog.findSalesGrossPriceCents(setOf(1L)),
                    "The same net amount at 7 percent is 428 gross",
                )
            }
        }
    }

    /**
     * One category with one subcategory, the VAT entry every price refers to, and the two slots the
     * composition test orders by.
     *
     * The slot ids run against their positions on purpose: slot 1 is "Style" at position 2, slot 2
     * is "Background" at position 1.
     */
    private fun seedCatalog(dataSource: DataSource) {
        PromptTestSchema.reset(dataSource)
        PromptTestSchema.seedVat(dataSource)
        PromptTestSchema.seedCategories(dataSource, "Portraits")
        PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Adults")
        PromptTestSchema.seedSlot(dataSource, "Style", position = 2)
        PromptTestSchema.seedSlot(dataSource, "Background", position = 1)
        // Variant 1 of the second slot, 2 and 3 of the first, 4 blank — so neither the id order nor
        // the insertion order is the composition order.
        PromptTestSchema.seedVariantWithText(dataSource, slotId = 1, "Watercolor", "in watercolor")
        PromptTestSchema.seedVariantWithText(dataSource, slotId = 2, "Meadow", "  in a meadow  ")
        PromptTestSchema.seedVariantWithText(dataSource, slotId = 2, "Beach", "on a beach")
        PromptTestSchema.seedVariantWithText(dataSource, slotId = 1, "Unset", "   ")
    }

    /**
     * A prompt body with the untrimmed text every composition assertion trims. [grossCents] and
     * [netCents] are the two ways to price it: a gross amount stays what the admin entered when the
     * VAT changes, a net one does not.
     */
    private fun promptBody(
        title: String,
        subcategoryId: Long? = null,
        variantIds: List<Long> = emptyList(),
        grossCents: Int? = 499,
        netCents: Int? = null,
    ): String {
        val price =
            when (netCents) {
                null ->
                    """{"purchaseVatId":1,"salesVatId":1,""" +
                        """"salesTotalInputCents":${grossCents ?: 0}}"""
                else ->
                    """{"purchaseVatId":1,"salesVatId":1,"salesCalculationMode":"NET",""" +
                        """"salesTotalInputCents":$netCents}"""
            }
        return """{"title":"$title","promptText":${JsonPrimitive(UNTRIMMED_TEXT)},""" +
            """"categoryId":1,"subcategoryId":${subcategoryId ?: "null"},""" +
            """"slotVariantIds":${variantIds.joinToString(",", "[", "]")},""" +
            """"exampleImageFilename":null,"llm":"gpt-image-1","active":true,""" +
            """"archived":false,"price":$price}"""
    }

    /**
     * Runs [block] against the real module installed on [dataSource], with an admin client that
     * writes the prompts and the capability the module returned when it was installed.
     */
    private fun catalogApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (CatalogFixture) -> Unit,
    ) = testApplication {
        lateinit var prices: CountingPriceCatalog
        lateinit var catalog: PromptCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validatePromptRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            catalog = installPromptModule(database, RecordingPublicImageStorage(), prices)
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
        block(CatalogFixture(admin, token, prices, catalog))
    }

    /** The signed-in admin client, the counted price capability, and the capability under test. */
    private class CatalogFixture(
        val admin: HttpClient,
        val token: String,
        val prices: CountingPriceCatalog,
        val catalog: PromptCatalog,
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
    }

    private companion object {
        /** The stored text: leading and trailing whitespace the module keeps verbatim. */
        const val UNTRIMMED_TEXT = "\n  Turn the photo into art.  \n"

        /** The same text as the composed one starts with. */
        const val TRIMMED_TEXT = "Turn the photo into art."

        /** Every seeded variant, including the blank one that contributes nothing. */
        val ALL_VARIANTS = listOf(1L, 2L, 3L, 4L)
    }
}

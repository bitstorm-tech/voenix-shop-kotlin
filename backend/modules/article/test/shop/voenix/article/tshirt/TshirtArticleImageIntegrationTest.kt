package shop.voenix.article.tshirt

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
import kotlin.test.assertNull
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
 * The two kinds of picture a t-shirt carries, against real Ktor routes and a real PostgreSQL
 * database: when a submitted file name is checked, and which file a write may delete once it has
 * committed.
 *
 * It is a slice of its own for the reason the mug slice has one: both questions are cross-row
 * questions. The variant array is a diff, so which file became an orphan is decided by the whole
 * array, and whether a file really is an orphan is decided by every other row that could name it —
 * every variant of the table for an example image, every shirt of the table for a size chart.
 */
internal class TshirtArticleImageIntegrationTest : PostgresIntegrationTest() {
    /**
     * The variant array is a diff, and the order its statements run in is what makes a default swap
     * possible at all: the partial unique index would reject the middle of it in any other order.
     */
    @Test
    fun `the variant array is a diff that swaps the default and deletes what it orphans`() {
        migratedDataSource("article-tshirt-variant-diff-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "article-tshirt-variant-diff-integration-session-secret",
            ) { admin, images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE)

                val created =
                    admin.createTshirt(
                        token,
                        draftBody(
                            "Classic tee",
                            variants =
                                listOf(
                                    variantBody(
                                        "Black",
                                        "M",
                                        isDefault = true,
                                        image = FIRST_IMAGE,
                                    ),
                                    variantBody(
                                        "White",
                                        "L",
                                        isDefault = false,
                                        image = SECOND_IMAGE,
                                    ),
                                ),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val id = body.number("id").toLong()
                val blackId = body.getValue("tshirtVariants").jsonArray[0].jsonObject.number("id")

                // Keep the default variant with a new image, drop the other one, add a third, and
                // move the default flag over to it.
                val updated =
                    admin.updateTshirt(
                        token,
                        id,
                        draftBody(
                            "Classic tee",
                            variants =
                                listOf(
                                    variantBody(
                                        "Black",
                                        "M",
                                        isDefault = false,
                                        image = THIRD_IMAGE,
                                        id = blackId.toLong(),
                                    ),
                                    variantBody("Blue", "S", isDefault = true),
                                ),
                        ),
                    )
                assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())

                assertEquals(
                    listOf("Black / M" to THIRD_IMAGE, "Blue / S" to null),
                    ArticleTestSchema.storedTshirtVariants(dataSource, id),
                )
                assertEquals(
                    listOf("Black / M" to false, "Blue / S" to true),
                    ArticleTestSchema.storedTshirtVariantDefaults(dataSource, id),
                )
                // Both orphans are gone: the image the removed variant held and the one the kept
                // variant replaced.
                assertEquals(setOf(FIRST_IMAGE, SECOND_IMAGE), images.deleted.toSet())
                assertEquals(listOf(THIRD_IMAGE), images.files)

                // A variant of another article cannot be addressed through this one.
                val other = admin.createTshirt(token, draftBody("Other tee"))
                val otherId = Json.parseToJsonElement(other.bodyAsText()).jsonObject.number("id")
                assertFieldError(
                    admin.updateTshirt(
                        token,
                        otherId.toLong(),
                        draftBody(
                            "Other tee",
                            variants =
                                listOf(
                                    variantBody(
                                        "Black",
                                        "M",
                                        isDefault = true,
                                        id = blackId.toLong(),
                                    )
                                ),
                        ),
                    ),
                    "tshirtVariants",
                    "One or more variants do not belong to this article",
                )
            }
        }
    }

    /**
     * Nothing makes an uploaded name exclusive: the pre-upload hands a client one name, and it may
     * put that name on two variants. A variant that drops it must not delete the picture the other
     * one still shows.
     */
    @Test
    fun `a shared example image survives the variant that drops it`() {
        migratedDataSource("article-tshirt-shared-image-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "article-tshirt-shared-image-integration-session-secret",
            ) { admin, images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE)

                val created =
                    admin.createTshirt(
                        token,
                        draftBody(
                            "Classic tee",
                            variants =
                                listOf(
                                    variantBody(
                                        "Black",
                                        "M",
                                        isDefault = true,
                                        image = FIRST_IMAGE,
                                    ),
                                    variantBody(
                                        "White",
                                        "L",
                                        isDefault = false,
                                        image = FIRST_IMAGE,
                                    ),
                                ),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val id = body.number("id").toLong()
                val keptId =
                    body.getValue("tshirtVariants").jsonArray[0].jsonObject.number("id").toLong()

                val kept =
                    variantBody("Black", "M", isDefault = true, image = FIRST_IMAGE, id = keptId)
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateTshirt(token, id, draftBody("Classic tee", variants = listOf(kept)))
                        .status,
                )
                assertEquals(emptyList(), images.deleted)
                assertEquals(listOf(FIRST_IMAGE), images.files)

                // Only the reference that really was the last one takes the file with it.
                val cleared = variantBody("Black", "M", isDefault = true, id = keptId)
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateTshirt(
                            token,
                            id,
                            draftBody("Classic tee", variants = listOf(cleared)),
                        )
                        .status,
                )
                assertEquals(listOf(FIRST_IMAGE), images.deleted)
                assertEquals(emptyList(), images.files)
            }
        }
    }

    /**
     * The size chart is the article's own picture, so its lifecycle is the article's: it is checked
     * before the write, the file a write replaced is deleted after the commit, and a chart two
     * shirts share survives the one that drops it.
     */
    @Test
    fun `the size chart is checked, replaced after the commit, and shared safely`() {
        migratedDataSource("article-tshirt-size-chart-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-size-chart-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE)

                assertFieldError(
                    admin.createTshirt(token, draftBody("Ghost", sizeChart = "chart.webp")),
                    "sizeChartImageFilename",
                    "Example image filename must be the name of an uploaded image",
                )
                assertFieldError(
                    admin.createTshirt(token, draftBody("Ghost", sizeChart = THIRD_IMAGE)),
                    "sizeChartImageFilename",
                    "Example image does not exist",
                )

                val created =
                    admin.createTshirt(token, draftBody("Classic tee", sizeChart = FIRST_IMAGE))
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                // A second shirt shares the same chart, so the first may not delete it.
                val second =
                    admin.createTshirt(token, draftBody("Second tee", sizeChart = FIRST_IMAGE))
                val secondId = Json.parseToJsonElement(second.bodyAsText()).jsonObject.number("id")

                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateTshirt(
                            token,
                            id.toLong(),
                            draftBody("Classic tee", sizeChart = SECOND_IMAGE),
                        )
                        .status,
                )
                assertEquals(
                    SECOND_IMAGE,
                    ArticleTestSchema.storedTshirtSizeChart(dataSource, id.toLong()),
                )
                assertEquals(emptyList(), images.deleted)

                // Once the last reference goes, the file goes with it.
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateTshirt(token, secondId.toLong(), draftBody("Second tee")).status,
                )
                assertNull(ArticleTestSchema.storedTshirtSizeChart(dataSource, secondId.toLong()))
                assertEquals(listOf(FIRST_IMAGE), images.deleted)
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Shirts", "Posters")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")
        ArticleTestSchema.seedSuppliers(dataSource, "Spreadconnect")
    }

    /** A draft shirt: nothing but its texts, its frame, and what the test is about. */
    private fun draftBody(
        name: String,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        supplierId: Long? = null,
        sizeChart: String? = null,
        withPrice: Boolean = false,
        salesTotalInputCents: Int = 1000,
        variants: List<String> = emptyList(),
    ): String {
        val references =
            listOfNotNull(
                    categoryId?.let { value -> ""","categoryId":$value""" },
                    subcategoryId?.let { value -> ""","subcategoryId":$value""" },
                    supplierId?.let { value -> ""","supplierId":$value""" },
                    sizeChart?.let { chart -> ""","sizeChartImageFilename":"$chart"""" },
                )
                .joinToString("")
        val price =
            if (withPrice) {
                ""","price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                    """"salesTotalInputCents":$salesTotalInputCents}"""
            } else {
                ""
            }
        return """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":false$references,$PRINT_FRAME,""" +
            """"tshirtVariants":[${variants.joinToString(",")}]$price}"""
    }

    private fun variantBody(
        colorName: String,
        sizeLabel: String,
        isDefault: Boolean,
        active: Boolean = false,
        image: String? = null,
        id: Long? = null,
        productTypeId: Long = 812,
    ): String =
        """{${id?.let { value -> """"id":$value,""" } ?: ""}"colorName":"$colorName",""" +
            """"colorHex":"#101010","sizeLabel":"$sizeLabel",""" +
            """"spodProductTypeId":$productTypeId,"spodAppearanceId":5,""" +
            """"spodSizeId":${sizeLabel.first().code},"isDefault":$isDefault,"active":$active""" +
            (image?.let { file -> ""","exampleImageFilename":"$file"""" } ?: "") +
            "}"

    private suspend fun HttpClient.createTshirt(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateTshirt(
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
        val errors = fieldErrors(response)
        assertTrue(field in errors, "Expected an error on $field but got $errors")
        assertEquals(listOf(message), errors[field])
    }

    private suspend fun fieldErrors(response: HttpResponse): Map<String, List<String>> {
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        return body.getValue("errors").jsonObject.mapValues { (_, messages) ->
            messages.jsonArray.map { message -> message.jsonPrimitive.content }
        }
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/articles/tshirts"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
        const val THIRD_IMAGE = "33333333-3333-4333-8333-333333333333.webp"

        /** The frame every body carries, because a shirt without one is not a described shirt. */
        const val PRINT_FRAME =
            """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40.5}"""
    }
}

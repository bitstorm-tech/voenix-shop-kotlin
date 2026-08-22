package shop.voenix.article.category

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.CountingDataSource
import shop.voenix.article.CountingPriceCatalog
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
 * The storefront navigation across both article types, against real Ktor routes and a real
 * PostgreSQL database.
 *
 * The point of the route is that it knows no types: a category is a menu entry when *some* visible
 * article sits in it, and the answer never says which kind. The tests are therefore written with
 * mugs and shirts in the same catalog — a category that only shirts fill appears exactly like one
 * that only mugs fill, and a category loses its entry only when the last visible article of *any*
 * type in it is gone.
 */
internal class PublicArticleCategoryIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the navigation is the categories that visible articles of any type use`() {
        migratedDataSource("article-public-categories-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-categories-integration-secret") {
                fixture ->
                // `Mugs` is filled by a mug without a subcategory and by one in `Classic`;
                // `Shirts` only by a shirt in `Slim`; `Empty` by nobody at all.
                fixture.createMug(mugBody("Plain mug", categoryId = 1))
                fixture.createMug(mugBody("Classic mug", categoryId = 1, subcategoryId = 1))
                fixture.createTshirt(tshirtBody("Slim tee", categoryId = 2, subcategoryId = 3))

                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_subcategories
                    SET example_image_filename = '$FIRST_IMAGE'
                    WHERE id = 3;
                    """
                        .trimIndent(),
                )

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_NAVIGATION),
                    Json.parseToJsonElement(fixture.categories().bodyAsText()),
                )
            }
        }
    }

    @Test
    fun `a category keeps its entry while any visible article of any type is left in it`() {
        migratedDataSource("article-public-categories-mixed-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-categories-mixed-secret") { fixture ->
                // One mug and one shirt in the very same category and subcategory.
                fixture.createMug(mugBody("Classic mug", categoryId = 1, subcategoryId = 1))
                fixture.createTshirt(tshirtBody("Classic tee", categoryId = 1, subcategoryId = 1))
                assertEquals(listOf(1L to listOf(1L)), fixture.navigation())

                // The mug goes: the shirt alone keeps both levels alive.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_mugs SET active = FALSE",
                )
                assertEquals(listOf(1L to listOf(1L)), fixture.navigation())

                // The shirt goes too, and the entry disappears with the last visible article.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_tshirts SET active = FALSE",
                )
                assertEquals(emptyList<Pair<Long, List<Long>>>(), fixture.navigation())

                // A shirt whose category is switched off is not a navigation entry either.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_tshirts SET active = TRUE;
                    UPDATE voenix.article_categories SET active = FALSE WHERE id = 1;
                    """
                        .trimIndent(),
                )
                assertEquals(emptyList<Pair<Long, List<Long>>>(), fixture.navigation())
            }
        }
    }

    /**
     * The mug-only navigation is gone rather than deprecated: it could only ever answer half a
     * menu, and there is no compatibility layer for it.
     */
    @Test
    fun `the removed mug categories route is not a path any more`() {
        migratedDataSource("article-public-categories-removed-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-categories-removed-secret") { fixture
                ->
                fixture.createMug(mugBody("Classic mug", categoryId = 1, subcategoryId = 1))

                assertEquals(HttpStatusCode.OK, fixture.categories().status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    fixture.anonymous.get("/api/articles/mugs/categories").status,
                )
            }
        }
    }

    /**
     * One query per article type and nothing per article, per category, or per subcategory: the
     * navigation of one article costs what the navigation of six costs.
     */
    @Test
    fun `the navigation runs two queries whatever the catalog holds`() {
        migratedDataSource("article-public-categories-statements-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            storefrontApplication(counting, "article-public-categories-statements-secret") { fixture
                ->
                fixture.createMug(mugBody("Plain mug", categoryId = 1))
                counting.statements.clear()
                assertEquals(listOf(1L to emptyList<Long>()), fixture.navigation())
                val forOneArticle = counting.normalizedStatements()

                fixture.createMug(mugBody("Classic mug", categoryId = 1, subcategoryId = 1))
                fixture.createTshirt(tshirtBody("Slim tee", categoryId = 2, subcategoryId = 3))
                fixture.createTshirt(tshirtBody("Classic tee", categoryId = 1, subcategoryId = 1))
                counting.statements.clear()
                assertEquals(
                    listOf(1L to listOf(1L), 2L to listOf(3L)),
                    fixture.navigation(),
                )
                val forFourArticles = counting.normalizedStatements()

                assertEquals(
                    forOneArticle,
                    forFourArticles,
                    "The navigation must run the same statements whatever the catalog holds",
                )
                assertEquals(
                    NAVIGATION_STATEMENT_COUNT,
                    forOneArticle.size,
                    "Statements: $forOneArticle",
                )
            }
        }
    }

    /** An empty catalog is an empty menu, not a menu of empty categories. */
    @Test
    fun `an empty catalog answers an empty array`() {
        migratedDataSource("article-public-categories-empty-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-categories-empty-secret") { fixture ->
                assertEquals("[]", fixture.categories().bodyAsText())
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs", "Shirts", "Empty")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic", "Travel")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Slim")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd")
    }

    private fun mugBody(
        name: String,
        categoryId: Long,
        subcategoryId: Long? = null,
    ): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":$categoryId""" +
            (subcategoryId?.let { id -> ""","subcategoryId":$id""" } ?: "") +
            ""","mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[{"name":"White","insideColorCode":"#fff",""" +
            """"outsideColorCode":"#fff","isDefault":true,"active":true}],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1490}}"""

    private fun tshirtBody(
        name: String,
        categoryId: Long,
        subcategoryId: Long? = null,
    ): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":$categoryId""" +
            (subcategoryId?.let { id -> ""","subcategoryId":$id""" } ?: "") +
            ""","printAspectRatio":"1:1",""" +
            """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40.5},""" +
            """"tshirtVariants":[{"colorName":"Black","colorHex":"#101010","sizeLabel":"M",""" +
            """"spodProductTypeId":812,"spodAppearanceId":5,"spodSizeId":77,""" +
            """"isDefault":true,"active":true}],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1990}}"""

    /**
     * Runs [block] against the real module installed on [dataSource], with an admin client that
     * writes the catalog and an anonymous client that reads the menu.
     */
    private fun storefrontApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (PublicFixture) -> Unit,
    ) = testApplication {
        lateinit var prices: CountingPriceCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            installArticleModule(
                database,
                RecordingPublicImageStorage(),
                prices,
                RecordingSupplierReader(mapOf(1L to "Porcelain Ltd")),
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
        val token =
            Json.parseToJsonElement(admin.get("/api/antiforgery/token").bodyAsText())
                .jsonObject
                .getValue("requestToken")
                .jsonPrimitive
                .content
        // The storefront client has no cookie jar, so it never carries the admin session.
        block(PublicFixture(admin, token, client))
    }

    /** The two clients a navigation test drives. */
    private class PublicFixture(
        val admin: HttpClient,
        val token: String,
        val anonymous: HttpClient,
    ) {
        suspend fun createMug(body: String) = create("/api/admin/articles/mugs", body)

        suspend fun createTshirt(body: String) = create("/api/admin/articles/tshirts", body)

        suspend fun categories(): HttpResponse = anonymous.get(PUBLIC_PATH)

        /** The menu as `category id to subcategory ids`, in the order it is answered. */
        suspend fun navigation(): List<Pair<Long, List<Long>>> =
            Json.parseToJsonElement(categories().bodyAsText()).jsonArray.map { category ->
                category.jsonObject.getValue("id").jsonPrimitive.long to
                    category.jsonObject.getValue("subcategories").jsonArray.map { subcategory ->
                        subcategory.jsonObject.getValue("id").jsonPrimitive.long
                    }
            }

        private suspend fun create(
            path: String,
            body: String,
        ) {
            val created =
                admin.post(path) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }
    }

    private companion object {
        const val PUBLIC_PATH = "/api/articles/categories"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME

        /** One query per article type, and nothing else. */
        const val NAVIGATION_STATEMENT_COUNT = 2

        val DOCUMENTED_NAVIGATION =
            """
            [
              {
                "id": 1,
                "name": "Mugs",
                "position": 1,
                "subcategories": [
                  {
                    "id": 1,
                    "name": "Classic",
                    "exampleImageFilename": null,
                    "position": 1
                  }
                ]
              },
              {
                "id": 2,
                "name": "Shirts",
                "position": 2,
                "subcategories": [
                  {
                    "id": 3,
                    "name": "Slim",
                    "exampleImageFilename": "$FIRST_IMAGE",
                    "position": 1
                  }
                ]
              }
            ]
            """
                .trimIndent()
    }
}

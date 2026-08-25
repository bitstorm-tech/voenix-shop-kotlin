package shop.voenix.article

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
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
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.country.Country
import shop.voenix.country.CountryReader
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.supplier.installSupplierModule
import shop.voenix.supplier.validateSupplierRequests
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The article-to-supplier relationship end to end, with both modules installed on one database and
 * driven through their real admin routes.
 *
 * This is the test the supplier migration deferred (`docs/migration/supplier-post-migration.md`).
 * `SupplierDeleteResult.InUse` was written when nothing referenced a supplier yet, so the outcome
 * was declared before it could be produced; a mug with a `supplier_id` is what produces it. The
 * restricted foreign key is the authority — the delete is attempted and PostgreSQL refuses it — so
 * what has to be proven here is that the refusal arrives as a `409` whose body carries neither the
 * constraint name nor the SQL message, and that nothing was deleted on the way.
 */
internal class ArticleSupplierRelationshipIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `deleting a referenced supplier is a 409 that leaks no schema names`() {
        migratedDataSource("article-supplier-in-use-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-supplier-in-use-integration-secret") { fixture ->
                fixture.createMug(mugBody(supplierId = 1))

                val refused = fixture.deleteSupplier(1)
                assertEquals(HttpStatusCode.Conflict, refused.status)
                val body = refused.bodyAsText()
                assertEquals(
                    "Supplier is in use and cannot be deleted",
                    Json.parseToJsonElement(body)
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content,
                )
                LEAKED_SCHEMA_TERMS.forEach { term ->
                    assertFalse(
                        body.contains(term, ignoreCase = true),
                        "The response must not expose `$term`: $body",
                    )
                }

                // Both rows survived the refused delete.
                assertEquals(listOf(1L, 2L), fixture.supplierIds())
                assertEquals(listOf(1L), fixture.storedMugSupplierIds(dataSource))
            }
        }
    }

    @Test
    fun `an unreferenced supplier is deletable and a referenced one becomes deletable again`() {
        migratedDataSource("article-supplier-release-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-supplier-release-integration-secret") { fixture ->
                fixture.createMug(mugBody(supplierId = 1))

                // The other supplier is referenced by nothing, so its delete is unaffected.
                assertEquals(HttpStatusCode.NoContent, fixture.deleteSupplier(2).status)
                assertEquals(listOf(1L), fixture.supplierIds())

                // Removing the last reference releases the supplier.
                assertEquals(HttpStatusCode.Conflict, fixture.deleteSupplier(1).status)
                assertEquals(HttpStatusCode.NoContent, fixture.deleteMug(1).status)
                assertEquals(HttpStatusCode.NoContent, fixture.deleteSupplier(1).status)
                assertEquals(emptyList(), fixture.supplierIds())

                // An id that is gone is a `404`, not the conflict of a referenced row.
                assertEquals(HttpStatusCode.NotFound, fixture.deleteSupplier(1).status)
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd", "Glass Co")
    }

    private fun mugBody(supplierId: Long): String =
        """{"name":"Classic mug","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":false,"categoryId":1,"supplierId":$supplierId,""" +
            """"supplierArticleNumber":"4711"}"""

    /** Runs [block] with Article **and** Supplier installed on one database, signed in as admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (RelationshipFixture) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) {
                validateArticleRequests()
                validateSupplierRequests()
            }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            val suppliers = installSupplierModule(database, NoCountries)
            installArticleModule(
                database,
                RecordingPublicImageStorage(),
                installPricingModule(database, installVatModule(database)),
                suppliers,
                unreachableSpodClient(),
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
        block(RelationshipFixture(admin, token))
    }

    /** The signed-in client and the two admin APIs this test drives against each other. */
    private class RelationshipFixture(
        val admin: HttpClient,
        val token: String,
    ) {
        suspend fun createMug(body: String) {
            val created =
                admin.post(MUG_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }

        suspend fun deleteMug(id: Long): HttpResponse =
            admin.delete("$MUG_PATH/$id") { header(AuthRouting.CSRF_HEADER, token) }

        suspend fun deleteSupplier(id: Long): HttpResponse =
            admin.delete("$SUPPLIER_PATH/$id") { header(AuthRouting.CSRF_HEADER, token) }

        /** The stored suppliers by id; the list itself answers in name order. */
        suspend fun supplierIds(): List<Long> =
            Json.parseToJsonElement(admin.get(SUPPLIER_PATH).bodyAsText())
                .jsonArray
                .map { supplier -> supplier.jsonObject.getValue("id").jsonPrimitive.long }
                .sorted()

        fun storedMugSupplierIds(dataSource: DataSource): List<Long> =
            ArticleTestSchema.storedMugSupplierIds(dataSource)
    }

    /** The supplier module needs a country capability; no supplier in this test has a country. */
    private object NoCountries : CountryReader {
        override suspend fun find(ids: Set<Long>): Map<Long, Country> = emptyMap()
    }

    private companion object {
        const val MUG_PATH = "/api/admin/articles/mugs"
        const val SUPPLIER_PATH = "/api/admin/suppliers"

        /**
         * What a leaked PostgreSQL error would put into the body: the constraint name, the table
         * behind it, and the wording of the driver's message.
         */
        val LEAKED_SCHEMA_TERMS =
            listOf("fk_article_mugs_supplier", "article_mugs", "constraint", "violates")
    }
}

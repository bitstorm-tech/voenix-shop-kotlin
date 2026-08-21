package shop.voenix.article.tshirt

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
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
 * Whether the display order of the t-shirts survives concurrent writers.
 *
 * The shirt positions are dense per article type, so every position writer queues on the
 * `article_types('TSHIRT')` row — the same anchor a mug write takes for `'MUG'`, one row per
 * sequence. These tests describe what that buys: concurrent creates append one after another
 * instead of reading the same maximum twice, and a delete running next to a create still leaves a
 * dense sequence.
 *
 * That the two anchors are two rows is what keeps the two article types out of each other's way,
 * and the last test says so: a shirt and a mug created at the same time both land on position 1.
 */
internal class TshirtArticleConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `concurrent creates append one after another`() {
        migratedDataSource("article-tshirt-concurrent-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)

                val responses = coroutineScope {
                    (1..CONCURRENT_WRITERS)
                        .map { number -> async { admin.createTshirt(token, "Tee $number") } }
                        .map { pending -> pending.await() }
                }

                responses.forEach { response ->
                    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                }
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    responses.map { response -> response.answeredPosition() }.sorted(),
                )
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    ArticleTestSchema.orderedTshirts(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a create running next to a delete cannot corrupt the sequence`() {
        migratedDataSource("article-tshirt-concurrent-delete-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                val first = admin.createTshirt(token, "First")
                admin.createTshirt(token, "Second")
                val firstId =
                    Json.parseToJsonElement(first.bodyAsText())
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content

                val results = coroutineScope {
                    val deleted = async {
                        admin.delete("$BASE_PATH/$firstId") {
                            header(AuthRouting.CSRF_HEADER, token)
                        }
                    }
                    val created = async { admin.createTshirt(token, "Third") }
                    listOf(deleted.await(), created.await())
                }

                assertEquals(HttpStatusCode.NoContent, results[0].status)
                assertEquals(HttpStatusCode.Created, results[1].status)
                // Whichever ran first, two shirts are left and their positions are dense.
                assertEquals(
                    listOf(1, 2),
                    ArticleTestSchema.orderedTshirts(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    /**
     * The two article types count their positions separately, and their anchors are two rows: a
     * shirt written next to a mug neither waits for it nor inherits its place.
     */
    @Test
    fun `a shirt and a mug created together both take position one`() {
        migratedDataSource("article-tshirt-type-anchor-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)

                val results = coroutineScope {
                    val shirt = async { admin.createTshirt(token, "Tee") }
                    val mug = async { admin.createMug(token, "Mug") }
                    listOf(shirt.await(), mug.await())
                }

                results.forEach { response ->
                    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                    assertEquals(1, response.answeredPosition())
                }
                assertEquals(listOf("Tee" to 1), ArticleTestSchema.orderedTshirts(dataSource))
                assertEquals(listOf("Mug" to 1), ArticleTestSchema.orderedMugs(dataSource))
            }
        }
    }

    private suspend fun HttpResponse.answeredPosition(): Int =
        Json.parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("position")
            .jsonPrimitive
            .content
            .toInt()

    private suspend fun HttpClient.createTshirt(
        token: String,
        name: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
                    """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40}}"""
            )
        }

    private suspend fun HttpClient.createMug(
        token: String,
        name: String,
    ): HttpResponse =
        post(MUG_BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","descriptionShort":"Short","descriptionLong":"Long"}""")
        }

    private fun adminApplication(
        dataSource: DataSource,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings("article-tshirt-concurrency-integration-secret"))
            val database = Database.connect(datasource = dataSource)
            installArticleModule(
                database,
                RecordingPublicImageStorage(),
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
        block(admin)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private companion object {
        const val BASE_PATH = "/api/admin/articles/tshirts"
        const val MUG_BASE_PATH = "/api/admin/articles/mugs"
        const val CONCURRENT_WRITERS = 4
    }
}

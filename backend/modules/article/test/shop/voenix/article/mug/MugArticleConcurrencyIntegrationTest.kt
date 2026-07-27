package shop.voenix.article.mug

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
 * Whether the display order of the mugs survives concurrent writers.
 *
 * Mug positions are dense per article type, so the row every position writer queues on is the
 * `article_types` row of its type. These tests describe what that buys: concurrent creates append
 * one after another instead of reading the same maximum twice, and a delete running next to them
 * still leaves a dense sequence.
 */
internal class MugArticleConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `concurrent creates append one after another`() {
        migratedDataSource("article-mug-concurrent-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)

                val responses = coroutineScope {
                    (1..CONCURRENT_WRITERS)
                        .map { number -> async { admin.createMug(token, "Mug $number") } }
                        .map { pending -> pending.await() }
                }

                responses.forEach { response ->
                    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                }
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    responses
                        .map { response ->
                            Json.parseToJsonElement(response.bodyAsText())
                                .jsonObject
                                .getValue("position")
                                .jsonPrimitive
                                .content
                                .toInt()
                        }
                        .sorted(),
                )
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    ArticleTestSchema.orderedMugs(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a create running next to a delete cannot corrupt the sequence`() {
        migratedDataSource("article-mug-concurrent-delete-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                val first = admin.createMug(token, "First")
                admin.createMug(token, "Second")
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
                    val created = async { admin.createMug(token, "Third") }
                    listOf(deleted.await(), created.await())
                }

                assertEquals(HttpStatusCode.NoContent, results[0].status)
                assertEquals(HttpStatusCode.Created, results[1].status)
                // Whichever of the two ran first, two mugs are left and their positions are dense.
                assertEquals(
                    listOf(1, 2),
                    ArticleTestSchema.orderedMugs(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    private suspend fun HttpClient.createMug(
        token: String,
        name: String,
    ): HttpResponse =
        post(BASE_PATH) {
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
            installAuthModule(AuthSettings("article-mug-concurrency-integration-session-secret"))
            val database = Database.connect(datasource = dataSource)
            installArticleModule(
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

    private companion object {
        const val BASE_PATH = "/api/admin/articles/mugs"
        const val CONCURRENT_WRITERS = 4
    }
}

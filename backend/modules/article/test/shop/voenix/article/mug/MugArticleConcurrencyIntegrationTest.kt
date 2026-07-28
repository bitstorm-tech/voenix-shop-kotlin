package shop.voenix.article.mug

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
 * Whether the display order of the mugs survives concurrent writers.
 *
 * Mug positions are dense per article type, so the row every position writer queues on is the
 * `article_types` row of its type — create, the compaction of delete, and reorder alike. These
 * tests describe what that buys, one per writer: concurrent creates append one after another
 * instead of reading the same maximum twice, a delete running next to a create still leaves a dense
 * sequence, and two reorders serialize instead of interleaving.
 *
 * The last two tests are about the writer the lock cannot reach — a manual database fix. A sequence
 * that already has a gap is refused before anything is written, and a position rewritten outside
 * the anchor makes the reorder lose the deferred unique check at COMMIT. Both answer the same
 * retryable `409`, and both leave the stored order exactly as they found it.
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

    @Test
    fun `two concurrent reorders serialize and leave a dense sequence`() {
        migratedDataSource("article-mug-concurrent-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                (1..4).forEach { number -> admin.createMug(token, "Mug $number") }

                val responses = coroutineScope {
                    val first = async { admin.reorder(token, sourceId = 4, targetId = 1) }
                    val second = async { admin.reorder(token, sourceId = 1, targetId = 3) }
                    listOf(first.await(), second.await())
                }

                // Both hold the type anchor, so neither may fail, and each answer is dense.
                responses.forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals(listOf(1, 2, 3, 4), response.answeredPositions())
                }
                assertEquals(
                    listOf(1, 2, 3, 4),
                    ArticleTestSchema.orderedMugs(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a create running next to a reorder cannot corrupt the sequence`() {
        migratedDataSource("article-mug-create-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                (1..4).forEach { number -> admin.createMug(token, "Mug $number") }

                val results = coroutineScope {
                    val created = async { admin.createMug(token, "Fifth") }
                    val reordered = async { admin.reorder(token, sourceId = 4, targetId = 1) }
                    listOf(created.await().status, reordered.await().status)
                }

                assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.OK), results)
                assertEquals(
                    listOf(1, 2, 3, 4, 5),
                    ArticleTestSchema.orderedMugs(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a gap in the stored sequence is refused before anything is written`() {
        migratedDataSource("article-mug-gapped-order-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                (1..3).forEach { number -> admin.createMug(token, "Mug $number") }
                // A writer that ignored the type anchor — a manual fix, for instance — left a gap.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_mugs SET position = 5 WHERE position = 3",
                )

                val response = admin.reorder(token, sourceId = 1, targetId = 2)
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(
                    "Article order changed concurrently, please retry",
                    Json.parseToJsonElement(response.bodyAsText())
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content,
                )
                // The broken sequence is not quietly repaired: nothing moved.
                assertEquals(
                    listOf("Mug 1" to 1, "Mug 2" to 2, "Mug 3" to 5),
                    ArticleTestSchema.orderedMugs(dataSource),
                )
            }
        }
    }

    @Test
    fun `a position written outside the ordering lock makes the reorder fail at commit`() {
        migratedDataSource("article-mug-commit-conflict-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)

            adminApplication(dataSource) { admin ->
                val token = antiforgeryToken(admin)
                (1..4).forEach { number -> admin.createMug(token, "Mug $number") }

                dataSource("article-mug-commit-conflict-raw").use { rawSource ->
                    rawSource.connection.use { raw ->
                        raw.autoCommit = false
                        // The rotation keeps the sequence dense, so what rejects the reorder is
                        // not the gap check but the deferred unique rule at COMMIT.
                        raw.createStatement().use { statement ->
                            statement.execute(
                                """
                                UPDATE voenix.article_mugs SET position = 2 WHERE id = 1;
                                UPDATE voenix.article_mugs SET position = 3 WHERE id = 2;
                                UPDATE voenix.article_mugs SET position = 4 WHERE id = 3;
                                UPDATE voenix.article_mugs SET position = 1 WHERE id = 4;
                                """
                                    .trimIndent()
                            )
                        }

                        val reordered = coroutineScope {
                            val pending = async { admin.reorder(token, sourceId = 4, targetId = 3) }
                            // The reorder read the old order and now waits for the rotated rows.
                            awaitBlockedMugWriter(rawSource)
                            raw.commit()
                            pending.await()
                        }

                        assertEquals(
                            HttpStatusCode.Conflict,
                            reordered.status,
                            reordered.bodyAsText(),
                        )
                    }
                }

                // The rejected transaction rolled back completely, so the rotation is what stands.
                assertEquals(
                    listOf("Mug 4" to 1, "Mug 1" to 2, "Mug 2" to 3, "Mug 3" to 4),
                    ArticleTestSchema.orderedMugs(dataSource),
                )
            }
        }
    }

    /** Waits until a statement is blocked on a row lock of the mug table. */
    private suspend fun awaitBlockedMugWriter(dataSource: DataSource) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (!hasBlockedMugWriter(dataSource)) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun hasBlockedMugWriter(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE '%article_mugs%'
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1) > 0
                    }
                }
        }

    private suspend fun HttpResponse.answeredPositions(): List<Int> =
        Json.parseToJsonElement(bodyAsText()).jsonArray.map { item ->
            item.jsonObject.getValue("position").jsonPrimitive.content.toInt()
        }

    private suspend fun HttpClient.reorder(
        token: String,
        sourceId: Long,
        targetId: Long,
    ): HttpResponse =
        put("$BASE_PATH/order") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceId":$sourceId,"targetId":$targetId}""")
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
        const val BASE_PATH = "/api/admin/articles/mugs"
        const val CONCURRENT_WRITERS = 4
        const val BLOCKED_WRITER_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}

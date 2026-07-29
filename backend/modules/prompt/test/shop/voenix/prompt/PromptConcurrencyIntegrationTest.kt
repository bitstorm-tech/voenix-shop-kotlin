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
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * Whether the display order of the prompts survives concurrent writers.
 *
 * Prompt positions are one global sequence — a prompt does not order inside its category — so every
 * writer that decides a position queues on the single `PROMPT` row of `prompt_ordering`. These
 * tests describe what that buys, one per writer: concurrent creates append one after another
 * instead of reading the same maximum twice, two reorders serialize instead of interleaving and
 * each answers a dense order, and a create running next to a reorder cannot corrupt the sequence.
 *
 * The last two tests are about the writer the lock cannot reach — a manual database fix. A sequence
 * that already has a gap is refused before anything is written, and a rotation committed outside
 * the anchor makes the reorder lose the deferred unique check at COMMIT. Both answer the same
 * retryable `409` with the same message, and both leave the stored order exactly as they found it.
 *
 * The tests run against the real routes and the real pricing module, because a prompt is only ever
 * created together with its price and this is what a concurrent client actually does.
 */
internal class PromptConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `concurrent creates append one after another`() {
        migratedDataSource("prompt-concurrent-create-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-concurrent-create-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val responses = coroutineScope {
                    (1..CONCURRENT_WRITERS)
                        .map { number -> async { admin.createPrompt(token, "Prompt $number") } }
                        .map { pending -> pending.await() }
                }

                responses.forEach { response ->
                    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                }
                // Nobody read the maximum another writer was about to take: 1..n, each once.
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    responses.map { response -> response.bodyObject().number("position") }.sorted(),
                )
                assertEquals(
                    (1..CONCURRENT_WRITERS).toList(),
                    PromptTestSchema.orderedPrompts(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `two concurrent reorders serialize and leave a dense sequence`() {
        migratedDataSource("prompt-concurrent-reorder-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-concurrent-reorder-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val ids = (1..4).map { number -> admin.createdPromptId(token, "Prompt $number") }

                val responses = coroutineScope {
                    val first = async { admin.reorder(token, ids[3], ids[0]) }
                    val second = async { admin.reorder(token, ids[0], ids[2]) }
                    listOf(first.await(), second.await())
                }

                // Both hold the `PROMPT` anchor, so neither may fail, and each answer is dense.
                responses.forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals(listOf(1, 2, 3, 4), response.answeredPositions())
                }
                assertEquals(
                    listOf(1, 2, 3, 4),
                    PromptTestSchema.orderedPrompts(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a create running next to a reorder cannot corrupt the sequence`() {
        migratedDataSource("prompt-create-reorder-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-create-reorder-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val ids = (1..4).map { number -> admin.createdPromptId(token, "Prompt $number") }

                val results = coroutineScope {
                    val created = async { admin.createPrompt(token, "Fifth") }
                    val reordered = async { admin.reorder(token, ids[3], ids[0]) }
                    listOf(created.await().status, reordered.await().status)
                }

                assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.OK), results)
                assertEquals(
                    listOf(1, 2, 3, 4, 5),
                    PromptTestSchema.orderedPrompts(dataSource).map { (_, position) -> position },
                )
            }
        }
    }

    @Test
    fun `a gap in the stored sequence is refused before anything is written`() {
        migratedDataSource("prompt-gapped-order-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-gapped-order-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val ids = (1..3).map { number -> admin.createdPromptId(token, "Prompt $number") }
                // A writer that ignored the ordering lock — a manual fix, for instance — left a
                // gap.
                PromptTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.prompts SET position = 5 WHERE position = 3",
                )

                val response = admin.reorder(token, ids[0], ids[1])
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(ORDER_CONFLICT_MESSAGE, response.bodyObject().text("message"))
                // The broken sequence is not quietly repaired: nothing moved.
                assertEquals(
                    listOf("Prompt 1" to 1, "Prompt 2" to 2, "Prompt 3" to 5),
                    PromptTestSchema.orderedPrompts(dataSource),
                )
            }
        }
    }

    @Test
    fun `a position written outside the ordering lock makes the reorder fail at commit`() {
        migratedDataSource("prompt-commit-conflict-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "prompt-commit-conflict-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val ids = (1..4).map { number -> admin.createdPromptId(token, "Prompt $number") }

                dataSource("prompt-commit-conflict-raw").use { rawSource ->
                    rawSource.connection.use { raw ->
                        raw.autoCommit = false
                        // The rotation keeps the sequence dense, so what rejects the reorder is
                        // not the gap check but the deferred unique rule at COMMIT.
                        raw.createStatement().use { statement ->
                            statement.execute(
                                """
                                UPDATE voenix.prompts SET position = 2 WHERE id = ${ids[0]};
                                UPDATE voenix.prompts SET position = 3 WHERE id = ${ids[1]};
                                UPDATE voenix.prompts SET position = 4 WHERE id = ${ids[2]};
                                UPDATE voenix.prompts SET position = 1 WHERE id = ${ids[3]};
                                """
                                    .trimIndent()
                            )
                        }

                        val reordered = coroutineScope {
                            val pending = async { admin.reorder(token, ids[3], ids[2]) }
                            // The reorder read the old order and now waits for the rotated rows.
                            awaitBlockedPromptWriter(rawSource)
                            raw.commit()
                            pending.await()
                        }

                        assertEquals(
                            HttpStatusCode.Conflict,
                            reordered.status,
                            reordered.bodyAsText(),
                        )
                        assertEquals(ORDER_CONFLICT_MESSAGE, reordered.bodyObject().text("message"))
                    }
                }

                // The rejected transaction rolled back completely, so the rotation is what stands.
                assertEquals(
                    listOf("Prompt 4" to 1, "Prompt 1" to 2, "Prompt 2" to 3, "Prompt 3" to 4),
                    PromptTestSchema.orderedPrompts(dataSource),
                )
            }
        }
    }

    /** Waits until a statement is blocked on a row lock of the prompt table. */
    private suspend fun awaitBlockedPromptWriter(dataSource: DataSource) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (!hasBlockedPromptWriter(dataSource)) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun hasBlockedPromptWriter(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE '%prompts%'
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
            item.jsonObject.number("position")
        }

    private suspend fun HttpResponse.bodyObject(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

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

    private suspend fun HttpClient.createPrompt(
        token: String,
        title: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"title":"$title","promptText":"Turn the photo into art.","categoryId":1,""" +
                    """"slotVariantIds":[],"active":true,"archived":false,""" +
                    """"price":{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499}}"""
            )
        }

    /** Creates one prompt and answers with the id it was given. */
    private suspend fun HttpClient.createdPromptId(
        token: String,
        title: String,
    ): Long {
        val response = createPrompt(token, title)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyObject().number("id").toLong()
    }

    private fun seedCatalog(dataSource: DataSource) {
        PromptTestSchema.reset(dataSource)
        PromptTestSchema.seedVat(dataSource)
        PromptTestSchema.seedCategories(dataSource, "Portraits")
    }

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

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/prompts"
        const val CONCURRENT_WRITERS = 4
        const val ORDER_CONFLICT_MESSAGE = "Prompt order changed concurrently, please retry"
        const val BLOCKED_WRITER_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}

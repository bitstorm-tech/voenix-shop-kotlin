package shop.voenix.promotion

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import java.math.BigDecimal
import java.time.Clock
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

internal class PromotionAdminCrudIntegrationTest : PostgresIntegrationTest() {
    private val validPromotionBody =
        """
        {"name":"Any sale","couponCode":"Any10","discountType":"PERCENTAGE","discountValue":10}
        """
            .trimIndent()

    @Test
    fun `admin can list and read seeded promotions with redemption counts`() {
        migratedDataSource("promotion-admin-read-test").use { dataSource ->
            seedPromotions(dataSource)

            adminApplication(dataSource, "promotion-admin-read-session-secret") { admin ->
                val listed =
                    Json.parseToJsonElement(admin.get("/api/admin/promotions").bodyAsText())
                        .jsonArray
                assertEquals(
                    listOf("Autumn sale", "Winter sale", "Winter sale"),
                    listed.map { it.jsonObject.getValue("name").jsonPrimitive.content },
                )
                assertEquals(
                    listOf(3L, 1L, 2L),
                    listed.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() },
                )

                val autumn = listed[0].jsonObject
                assertEquals("Autumn5", autumn.getValue("couponCode").jsonPrimitive.content)
                assertEquals(
                    """{"discountType":"FIXED_AMOUNT","discountValue":500.00}""",
                    autumn.getValue("discount").toString(),
                )
                assertEquals(0L, autumn.getValue("redemptionCount").jsonPrimitive.content.toLong())
                assertEquals(false, autumn.getValue("isLocked").jsonPrimitive.content.toBoolean())
                assertEquals("null", autumn.getValue("startsAt").toString())

                val winter = listed[1].jsonObject
                assertEquals(
                    """{"discountType":"PERCENTAGE","discountValue":10.00}""",
                    winter.getValue("discount").toString(),
                )
                assertEquals(2L, winter.getValue("redemptionCount").jsonPrimitive.content.toLong())
                assertEquals(true, winter.getValue("isLocked").jsonPrimitive.content.toBoolean())
                assertEquals(
                    "2026-01-01T00:00:00Z",
                    winter.getValue("startsAt").jsonPrimitive.content,
                )
                assertEquals(
                    "2026-03-01T00:00:00Z",
                    winter.getValue("endsAt").jsonPrimitive.content,
                )
                assertEquals(100, winter.getValue("usageLimitTotal").jsonPrimitive.content.toInt())
                assertEquals(1, winter.getValue("usageLimitPerUser").jsonPrimitive.content.toInt())

                val fetched = admin.get("/api/admin/promotions/1")
                assertEquals(HttpStatusCode.OK, fetched.status)
                assertEquals(
                    winter,
                    Json.parseToJsonElement(fetched.bodyAsText()).jsonObject,
                )

                val missing = admin.get("/api/admin/promotions/404")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    Json.parseToJsonElement("""{"message":"Promotion not found","errors":{}}"""),
                    Json.parseToJsonElement(missing.bodyAsText()),
                )
            }
        }
    }

    @Test
    fun `admin create trims values normalizes the code and rejects case-insensitive duplicates`() {
        migratedDataSource("promotion-admin-create-test").use { dataSource ->
            resetPromotions(dataSource)

            adminApplication(dataSource, "promotion-admin-create-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val created =
                    admin.post("/api/admin/promotions") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "name":"  Summer sale  ",
                              "couponCode":"  Sommer25  ",
                              "discountType":"FIXED_AMOUNT",
                              "discountValue":500,
                              "startsAt":"2026-06-01T00:00:00+02:00",
                              "endsAt":"2026-09-01T00:00:00Z",
                              "usageLimitTotal":100,
                              "usageLimitPerUser":2,
                              "isActive":true
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val id = createdBody.getValue("id").jsonPrimitive.content.toLong()
                assertEquals(
                    "/api/admin/promotions/$id",
                    created.headers[HttpHeaders.Location],
                )
                assertEquals("Summer sale", createdBody.getValue("name").jsonPrimitive.content)
                assertEquals("Sommer25", createdBody.getValue("couponCode").jsonPrimitive.content)
                assertEquals(
                    """{"discountType":"FIXED_AMOUNT","discountValue":500.00}""",
                    createdBody.getValue("discount").toString(),
                )
                assertEquals(
                    "2026-05-31T22:00:00Z",
                    createdBody.getValue("startsAt").jsonPrimitive.content,
                )
                assertEquals(
                    "2026-09-01T00:00:00Z",
                    createdBody.getValue("endsAt").jsonPrimitive.content,
                )
                assertEquals(
                    100,
                    createdBody.getValue("usageLimitTotal").jsonPrimitive.content.toInt(),
                )
                assertEquals(
                    2,
                    createdBody.getValue("usageLimitPerUser").jsonPrimitive.content.toInt(),
                )
                assertEquals(
                    true,
                    createdBody.getValue("isActive").jsonPrimitive.content.toBoolean(),
                )
                assertEquals(
                    0L,
                    createdBody.getValue("redemptionCount").jsonPrimitive.content.toLong(),
                )
                assertEquals(
                    false,
                    createdBody.getValue("isLocked").jsonPrimitive.content.toBoolean(),
                )

                assertEquals(
                    createdBody,
                    Json.parseToJsonElement(admin.get("/api/admin/promotions/$id").bodyAsText())
                        .jsonObject,
                )
                assertEquals("SOMMER25", normalizedCodeOf(dataSource, id))

                val duplicate =
                    admin.post("/api/admin/promotions") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"name":"Another sale","couponCode":" sOMMER25 ",
                             "discountType":"PERCENTAGE","discountValue":10}
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                assertEquals(
                    Json.parseToJsonElement(
                        """{"message":"Coupon code is already in use","errors":{}}"""
                    ),
                    Json.parseToJsonElement(duplicate.bodyAsText()),
                )

                val listed =
                    Json.parseToJsonElement(admin.get("/api/admin/promotions").bodyAsText())
                        .jsonArray
                assertEquals(1, listed.size)
            }
        }
    }

    @Test
    fun `admin fully updates an unredeemed promotion and cannot update an unknown one`() {
        migratedDataSource("promotion-admin-update-test").use { dataSource ->
            seedPromotions(dataSource)

            adminApplication(dataSource, "promotion-admin-update-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.put("/api/admin/promotions/3") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "name":"  Late autumn sale  ",
                              "couponCode":"  Herbst9  ",
                              "discountType":"PERCENTAGE",
                              "discountValue":9.5,
                              "startsAt":"2026-10-01T02:00:00+02:00",
                              "endsAt":"2026-11-01T00:00:00Z",
                              "usageLimitTotal":50,
                              "usageLimitPerUser":3,
                              "isActive":false
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals(3L, body.getValue("id").jsonPrimitive.content.toLong())
                assertEquals("Late autumn sale", body.getValue("name").jsonPrimitive.content)
                assertEquals("Herbst9", body.getValue("couponCode").jsonPrimitive.content)
                assertEquals(
                    """{"discountType":"PERCENTAGE","discountValue":9.50}""",
                    body.getValue("discount").toString(),
                )
                assertEquals(
                    "2026-10-01T00:00:00Z",
                    body.getValue("startsAt").jsonPrimitive.content,
                )
                assertEquals("2026-11-01T00:00:00Z", body.getValue("endsAt").jsonPrimitive.content)
                assertEquals(50, body.getValue("usageLimitTotal").jsonPrimitive.content.toInt())
                assertEquals(3, body.getValue("usageLimitPerUser").jsonPrimitive.content.toInt())
                assertEquals(false, body.getValue("isActive").jsonPrimitive.content.toBoolean())
                assertEquals(0L, body.getValue("redemptionCount").jsonPrimitive.content.toLong())
                assertEquals(false, body.getValue("isLocked").jsonPrimitive.content.toBoolean())

                assertEquals(
                    body,
                    Json.parseToJsonElement(admin.get("/api/admin/promotions/3").bodyAsText())
                        .jsonObject,
                )
                assertEquals("HERBST9", normalizedCodeOf(dataSource, 3))

                val missing =
                    admin.put("/api/admin/promotions/404") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(validPromotionBody)
                    }
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    Json.parseToJsonElement("""{"message":"Promotion not found","errors":{}}"""),
                    Json.parseToJsonElement(missing.bodyAsText()),
                )
            }
        }
    }

    @Test
    fun `a redeemed promotion rejects configuration changes but accepts activation changes`() {
        migratedDataSource("promotion-locked-update-test").use { dataSource ->
            seedPromotions(dataSource)

            adminApplication(dataSource, "promotion-locked-update-session-secret") { admin ->
                val token = antiforgeryToken(admin)
                val before =
                    Json.parseToJsonElement(admin.get("/api/admin/promotions/1").bodyAsText())
                        .jsonObject

                val rejected =
                    admin.put("/api/admin/promotions/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"name":"Winter sale reloaded","couponCode":"Winter10",
                             "discountType":"PERCENTAGE","discountValue":10.00,
                             "startsAt":"2026-01-01T00:00:00Z","endsAt":"2026-03-01T00:00:00Z",
                             "usageLimitTotal":100,"usageLimitPerUser":1,"isActive":true}
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Conflict, rejected.status)
                assertEquals(
                    Json.parseToJsonElement(
                        """
                        {"message":"Coupon code is already in use or the promotion is locked",
                         "errors":{}}
                        """
                            .trimIndent()
                    ),
                    Json.parseToJsonElement(rejected.bodyAsText()),
                )
                assertEquals(
                    before,
                    Json.parseToJsonElement(admin.get("/api/admin/promotions/1").bodyAsText())
                        .jsonObject,
                )

                // Same configuration, different activity window notation, only isActive differs.
                val deactivated =
                    admin.put("/api/admin/promotions/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"name":"Winter sale","couponCode":"Winter10",
                             "discountType":"PERCENTAGE","discountValue":10,
                             "startsAt":"2026-01-01T01:00:00+01:00",
                             "endsAt":"2026-03-01T00:00:00Z",
                             "usageLimitTotal":100,"usageLimitPerUser":1,"isActive":false}
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.OK, deactivated.status)
                assertEquals(
                    before.toMutableMap().apply { put("isActive", JsonPrimitive(false)) },
                    Json.parseToJsonElement(deactivated.bodyAsText()).jsonObject.toMap(),
                )
            }
        }
    }

    @Test
    fun `an update to a case-variant coupon code of another promotion conflicts`() {
        migratedDataSource("promotion-update-duplicate-test").use { dataSource ->
            seedPromotions(dataSource)

            adminApplication(dataSource, "promotion-update-duplicate-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val duplicate =
                    admin.put("/api/admin/promotions/2") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"name":"Winter sale","couponCode":" wInTeR10 ",
                             "discountType":"PERCENTAGE","discountValue":15}
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                assertEquals(
                    "Winter15",
                    Json.parseToJsonElement(admin.get("/api/admin/promotions/2").bodyAsText())
                        .jsonObject
                        .getValue("couponCode")
                        .jsonPrimitive
                        .content,
                )
            }
        }
    }

    @Test
    fun `delete removes an unredeemed promotion and refuses unknown and redeemed ones`() {
        migratedDataSource("promotion-admin-delete-test").use { dataSource ->
            seedPromotions(dataSource)

            adminApplication(dataSource, "promotion-admin-delete-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val deleted =
                    admin.delete("/api/admin/promotions/3") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())
                assertEquals(
                    HttpStatusCode.NotFound,
                    admin.get("/api/admin/promotions/3").status,
                )

                val missing =
                    admin.delete("/api/admin/promotions/3") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.NotFound, missing.status)

                val redeemed =
                    admin.delete("/api/admin/promotions/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.Conflict, redeemed.status)
                assertEquals(
                    Json.parseToJsonElement(
                        """
                        {"message":"Promotion has redemptions and cannot be deleted","errors":{}}
                        """
                            .trimIndent()
                    ),
                    Json.parseToJsonElement(redeemed.bodyAsText()),
                )
                assertEquals(HttpStatusCode.OK, admin.get("/api/admin/promotions/1").status)
            }
        }
    }

    @Test
    fun `concurrent case-variant creates leave one row and one conflict`() {
        migratedDataSource("promotion-concurrent-create-test").use { dataSource ->
            resetPromotions(dataSource)
            val service =
                PromotionService(
                    PromotionRepository(Database.connect(datasource = dataSource)),
                    Clock.systemUTC(),
                )

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async {
                                service.create(promotionInput(name = "Race A", code = "Race10"))
                            },
                            async {
                                service.create(promotionInput(name = "Race B", code = "RACE10"))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { it is OperationResult.Success })
                assertEquals(1, results.count { it === OperationResult.Conflict })
                val listed = assertIs<OperationResult.Success<List<Promotion>>>(service.list())
                assertEquals(1, listed.value.size)
            }
        }
    }

    @Test
    fun `concurrent case-variant updates leave one code and one conflict`() {
        migratedDataSource("promotion-concurrent-update-test").use { dataSource ->
            seedPromotions(dataSource)
            val service =
                PromotionService(
                    PromotionRepository(Database.connect(datasource = dataSource)),
                    Clock.systemUTC(),
                )

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async {
                                service.update(
                                    2,
                                    promotionInput(name = "Race A", code = "Race10"),
                                )
                            },
                            async {
                                service.update(
                                    3,
                                    promotionInput(name = "Race B", code = "RACE10"),
                                )
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { it is OperationResult.Success })
                assertEquals(1, results.count { it === OperationResult.Conflict })
                val listed = assertIs<OperationResult.Success<List<Promotion>>>(service.list())
                assertEquals(
                    listOf("RACE10"),
                    listed.value
                        .map(Promotion::couponCode)
                        .filter { code -> code.equals("Race10", ignoreCase = true) }
                        .map(String::uppercase),
                )
            }
        }
    }

    @Test
    fun `service validates defensively and rejects invalid input before persistence`() {
        migratedDataSource("promotion-defensive-validation-test").use { dataSource ->
            resetPromotions(dataSource)
            val service =
                PromotionService(
                    PromotionRepository(Database.connect(datasource = dataSource)),
                    Clock.systemUTC(),
                )

            runBlocking {
                val invalid = service.create(PromotionInput(name = "Broken"))
                val errors = assertIs<OperationResult.Invalid>(invalid).errors
                assertEquals(
                    setOf("couponCode", "discountType", "discountValue"),
                    errors.keys,
                )

                val stillValid = service.create(promotionInput(name = "Valid", code = "Valid10"))
                assertIs<OperationResult.Success<Promotion>>(stillValid)
                val listed = assertIs<OperationResult.Success<List<Promotion>>>(service.list())
                assertEquals(listOf("Valid"), listed.value.map(Promotion::name))
            }
        }
    }

    @Test
    fun `database failures during create are hidden behind unexpected failure results`() {
        val dataSource = migratedDataSource("promotion-create-failure-test")
        resetPromotions(dataSource)
        val service =
            PromotionService(
                PromotionRepository(Database.connect(datasource = dataSource)),
                Clock.systemUTC(),
            )
        dataSource.close()

        runBlocking {
            assertSame(
                OperationResult.UnexpectedFailure,
                service.create(promotionInput(name = "Broken pool", code = "Broken10")),
            )
        }
    }

    /**
     * Runs [block] against the real module installed on [dataSource], with a client that is already
     * signed in as an administrator.
     */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) { validatePromotionRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            installPromotionModule(Database.connect(datasource = dataSource))
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

    private fun promotionInput(
        name: String,
        code: String,
    ): PromotionInput =
        PromotionInput(
            name = name,
            couponCode = code,
            discountType = "PERCENTAGE",
            discountValue = BigDecimal("10.00"),
            isActive = true,
        )

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private fun normalizedCodeOf(
        dataSource: DataSource,
        id: Long,
    ): String =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT coupon_code_normalized FROM voenix.promotions WHERE id = ?"
                )
                .use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Promotion $id not found" }
                        rows.getString(1)
                    }
                }
        }

    private fun resetPromotions(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "DELETE FROM voenix.promotion_redemptions; DELETE FROM voenix.promotions;"
                )
            }
        }
    }

    private fun seedPromotions(dataSource: DataSource) {
        resetPromotions(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO voenix.promotions (
                        id, name, discount_type, discount_value, coupon_code,
                        coupon_code_normalized, starts_at, ends_at,
                        usage_limit_total, usage_limit_per_user, is_active
                    ) VALUES
                        (1, 'Winter sale', 'PERCENTAGE', 10.00, 'Winter10', 'WINTER10',
                         '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', 100, 1, TRUE),
                        (2, 'Winter sale', 'PERCENTAGE', 15.00, 'Winter15', 'WINTER15',
                         NULL, NULL, NULL, NULL, FALSE),
                        (3, 'Autumn sale', 'FIXED_AMOUNT', 500.00, 'Autumn5', 'AUTUMN5',
                         NULL, NULL, NULL, NULL, TRUE);
                    INSERT INTO voenix.promotion_redemptions (promotion_id, user_id, redeemed_at)
                    VALUES
                        (1, 42, '2026-02-01T10:00:00Z'),
                        (1, NULL, '2026-02-02T10:00:00Z');
                    """
                        .trimIndent()
                )
            }
        }
    }
}

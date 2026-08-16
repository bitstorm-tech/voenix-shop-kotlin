package shop.voenix.vat

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

/**
 * A VAT test writes a `voenix.prices` row on purpose: `prices` is the only table with a foreign key
 * into `value_added_taxes`, and that foreign key is what makes a referenced VAT entry undeletable.
 * The rule itself is VAT's own, so its coverage lives in the VAT module rather than in Pricing. The
 * row is written with raw SQL because the Pricing module is not a dependency here.
 */
internal class VatDeleteInUseIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a vat referenced by a price cannot be deleted until the price is gone`() = runBlocking {
        migratedDataSource("vat-delete-in-use-service-test").use { dataSource ->
            resetVatsAndPrices(dataSource)
            val service = VatService(VatRepository(Database.connect(datasource = dataSource)))

            val vat =
                assertIs<OperationResult.Success<Vat>>(
                        service.create(VatInput("Standard", 19, isDefault = true))
                    )
                    .value
            insertPriceReferencing(dataSource, vat.id)

            assertSame(OperationResult.Conflict, service.delete(vat.id))
            assertIs<OperationResult.Success<Vat>>(service.get(vat.id))

            deletePrices(dataSource)
            assertIs<OperationResult.Success<Unit>>(service.delete(vat.id))
            assertSame(OperationResult.NotFound, service.get(vat.id))
        }
    }

    @Test
    fun `deleting a referenced vat answers 409 vat is in use`() {
        migratedDataSource("vat-delete-in-use-http-test").use { dataSource ->
            resetVatsAndPrices(dataSource)
            val database = Database.connect(datasource = dataSource)
            val vat = runBlocking {
                assertIs<OperationResult.Success<Vat>>(
                        VatService(VatRepository(database))
                            .create(VatInput("Standard", 19, isDefault = true))
                    )
                    .value
            }
            insertPriceReferencing(dataSource, vat.id)

            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(AuthSettings("vat-delete-in-use-session-secret-for-tests"))
                    installVatModule(database)
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

                val response =
                    admin.delete("/api/admin/vat/${vat.id}") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
                // `ApiError.errors` always serializes, so an error body without field errors
                // carries an empty object here.
                assertEquals("""{"message":"VAT is in use","errors":{}}""", response.bodyAsText())
            }
        }
    }

    private fun resetVatsAndPrices(dataSource: HikariDataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.prices, voenix.value_added_taxes RESTART IDENTITY CASCADE",
        )
    }

    private fun deletePrices(dataSource: HikariDataSource) {
        execute(dataSource, "DELETE FROM voenix.prices")
    }

    /**
     * Every non-identity column of `voenix.prices` is `NOT NULL`, so the insert lists all of them.
     * The VAT entry is referenced twice, as the purchase and as the sales VAT.
     */
    private fun insertPriceReferencing(
        dataSource: HikariDataSource,
        vatId: Long,
    ) {
        execute(
            dataSource,
            """
            INSERT INTO voenix.prices (
                purchase_vat_id,
                purchase_calculation_mode,
                purchase_active_row,
                purchase_price_input_cents,
                purchase_cost_input_cents,
                purchase_cost_percent,
                sales_vat_id,
                sales_calculation_mode,
                sales_active_row,
                sales_margin_input_cents,
                sales_margin_percent,
                sales_total_input_cents
            ) VALUES (
                $vatId, 'NET', 'COST', 0, 0, 0,
                $vatId, 'GROSS', 'TOTAL', 0, 0, 1190
            )
            """
                .trimIndent(),
        )
    }

    private fun execute(
        dataSource: HikariDataSource,
        sql: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }
}

package shop.voenix.magiccoins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

internal class MagicCoinsBalanceRouteFailureTest {
    @Test
    fun `an unexpected failure becomes the standard 500 response and stays uncached`() {
        testApplication {
            val authSettings = AuthSettings("magic-coins-failure-test-secret-32-bytes!")
            application {
                installHttpRuntime()
                installAuthModule(authSettings)
                installMagicCoinsModule(FailingOperations, GuestTokens(authSettings))
            }

            val response = client.get("/api/magic-coins/balance")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(
                """{"message":"Internal server error","errors":{}}""",
                response.bodyAsText(),
            )
        }
    }

    private object FailingOperations : MagicCoinsOperations {
        override suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int> =
            OperationResult.UnexpectedFailure

        override suspend fun hasEnoughForGeneration(
            owner: MagicCoinsOwner
        ): OperationResult<Boolean> = OperationResult.UnexpectedFailure

        override suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean = false
    }
}

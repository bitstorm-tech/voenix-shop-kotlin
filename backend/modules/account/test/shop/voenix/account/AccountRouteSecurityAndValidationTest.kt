package shop.voenix.account

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

internal class AccountRouteSecurityAndValidationTest {
    @Test
    fun `authenticated routes reject missing sessions before reaching the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installAccountTestApplication(accounts) }

            listOf(
                    client.get("/api/auth/me"),
                    client.put("/api/auth/profile"),
                    client.post("/api/auth/change-email"),
                    client.post("/api/auth/change-password"),
                    client.post("/api/auth/logout"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    assertTrue(response.bodyAsText().contains("Authentication required"))
                }
            assertEquals(0, accounts.operationCalls)
        }

    @Test
    fun `authenticated mutations reject missing or invalid csrf before the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installAccountTestApplication(accounts) }
            val customer = signedInClient()

            assertEquals(HttpStatusCode.OK, customer.get("/api/auth/me").status)

            listOf(
                    customer.put("/api/auth/profile"),
                    customer.post("/api/auth/change-email"),
                    customer.post("/api/auth/change-password"),
                    customer.post("/api/auth/logout"),
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }

            listOf(
                    customer.put("/api/auth/profile") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                    customer.post("/api/auth/change-email") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                    customer.post("/api/auth/change-password") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                    customer.post("/api/auth/logout") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }

            assertEquals(1, accounts.operationCalls, "only GET me may reach the operation")
        }

    @Test
    fun `invalid bodies are rejected by the shared request validation before the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installAccountTestApplication(accounts) }

            val invalidRegister =
                client.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"not-an-email","password":"1234567"}""")
                }
            assertApiError(
                invalidRegister,
                HttpStatusCode.BadRequest,
                "Validation failed",
                mapOf(
                    "email" to listOf("Invalid email format"),
                    "password" to listOf("Password must be at least 8 characters"),
                ),
            )

            val invalidLogin =
                client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"","password":""}""")
                }
            assertApiError(
                invalidLogin,
                HttpStatusCode.BadRequest,
                "Validation failed",
                mapOf(
                    "email" to listOf("Email is required"),
                    "password" to listOf("Password is required"),
                ),
            )

            listOf("", "null", "[]", """{"email":""").forEach { body ->
                val response =
                    client.post("/api/auth/forgot-password") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid request body")
            }

            val customer = signedInClient()
            val token = antiforgeryToken(customer)
            val invalidProfile =
                customer.put("/api/auth/profile") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"hasSeparateBillingAddress":false}""")
                }
            assertApiError(
                invalidProfile,
                HttpStatusCode.BadRequest,
                "Validation failed",
                mapOf("shippingAddress" to listOf("Shipping address is required")),
            )

            assertEquals(0, accounts.operationCalls)
        }

    @Test
    fun `operation outcomes map to the documented statuses`() = testApplication {
        val accounts = StubAccountOperations()
        application { installAccountTestApplication(accounts) }

        accounts.registerResult = RegisterResult.EmailTaken
        assertApiError(
            client.postRegister(),
            HttpStatusCode.Conflict,
            "Email already exists",
        )
        accounts.registerResult = RegisterResult.DeliveryFailed
        assertApiError(
            client.postRegister(),
            HttpStatusCode.BadGateway,
            "Confirmation email could not be delivered",
        )
        accounts.registerResult = RegisterResult.UnexpectedFailure
        assertApiError(
            client.postRegister(),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        accounts.loginResult = LoginResult.InvalidCredentials
        assertApiError(
            client.postLogin(),
            HttpStatusCode.Unauthorized,
            "Invalid email or password",
        )
        accounts.loginResult = LoginResult.EmailNotConfirmed
        assertApiError(client.postLogin(), HttpStatusCode.Forbidden, "Email is not confirmed")
        accounts.loginResult = LoginResult.LockedOut
        assertApiError(
            client.postLogin(),
            HttpStatusCode.TooManyRequests,
            "Too many failed login attempts",
        )

        val confirm =
            client.post("/api/auth/confirm-email") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":1,"token":"stale"}""")
            }
        assertApiError(
            confirm,
            HttpStatusCode.BadRequest,
            "Invalid or expired confirmation link",
        )
        val reset =
            client.post("/api/auth/reset-password") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"email":"user@example.com","token":"stale","newPassword":"password-2"}"""
                )
            }
        assertApiError(
            reset,
            HttpStatusCode.BadRequest,
            "Invalid or expired password reset link",
        )
    }

    private fun Application.installAccountTestApplication(accounts: AccountOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateAccountRequests() }
        installAuthModule(AuthSettings("account-route-contract-session-secret"))
        installAccountModule(accounts)
        routing {
            post("/test/sign-in") {
                call.sessions.set(UserSession(userId = "11", role = "CUSTOMER"))
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { client -> assertEquals(HttpStatusCode.OK, client.post("/test/sign-in").status) }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val body = client.get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private suspend fun HttpClient.postRegister(): HttpResponse =
        post("/api/auth/register") {
            jsonBody("""{"email":"user@example.com","password":"password-1"}""")
        }

    private suspend fun HttpClient.postLogin(): HttpResponse =
        post("/api/auth/login") {
            jsonBody("""{"email":"user@example.com","password":"password-1"}""")
        }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
    ) {
        assertEquals(status, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject, body)
    }

    private class StubAccountOperations : AccountOperations {
        var operationCalls = 0
            private set

        var registerResult: RegisterResult = RegisterResult.Registered
        var loginResult: LoginResult = LoginResult.SignedIn(11, setOf("CUSTOMER"))

        override suspend fun register(input: RegisterInput): RegisterResult {
            operationCalls++
            return registerResult
        }

        override suspend fun login(input: LoginInput): LoginResult {
            operationCalls++
            return loginResult
        }

        override suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit> {
            operationCalls++
            return OperationResult.Invalid(emptyMap())
        }

        override suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit> {
            operationCalls++
            return OperationResult.Success(Unit)
        }

        override suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit> {
            operationCalls++
            return OperationResult.Success(Unit)
        }

        override suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit> {
            operationCalls++
            return OperationResult.Invalid(emptyMap())
        }

        override suspend fun profile(userId: Long): OperationResult<AccountProfile> {
            operationCalls++
            return OperationResult.Success(profile(userId, "user@example.com"))
        }

        override suspend fun updateProfile(
            userId: Long,
            input: ProfileInput,
        ): OperationResult<AccountProfile> {
            operationCalls++
            return OperationResult.Success(profile(userId, "user@example.com"))
        }

        override suspend fun changeEmail(
            userId: Long,
            input: ChangeEmailInput,
        ): ChangeEmailResult {
            operationCalls++
            return ChangeEmailResult.ConfirmationSent
        }

        override suspend fun confirmChangeEmail(
            input: ConfirmChangeEmailInput
        ): OperationResult<Unit> {
            operationCalls++
            return OperationResult.Success(Unit)
        }

        override suspend fun changePassword(
            userId: Long,
            input: ChangePasswordInput,
        ): ChangePasswordResult {
            operationCalls++
            return ChangePasswordResult.Changed
        }

        private fun profile(userId: Long, email: String): AccountProfile =
            AccountProfile(
                id = userId,
                email = email,
                roles = listOf("CUSTOMER"),
                shippingAddress = null,
                billingAddress = null,
                hasSeparateBillingAddress = false,
                createdAt = "2026-07-24T10:00:00Z",
            )
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}

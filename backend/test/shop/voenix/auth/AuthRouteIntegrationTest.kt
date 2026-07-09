package shop.voenix.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import shop.voenix.module
import shop.voenix.db.DatabaseFactory
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthRouteIntegrationTest : PostgresIntegrationTest() {
    private var databaseFactory: DatabaseFactory? = null

    @AfterTest
    fun closeDatabase() {
        databaseFactory?.close()
    }

    @Test
    fun `login logout admin role and csrf proof flow`() = testApplication {
        val repository = authRepository()
        val suffix = System.nanoTime()
        val adminEmail = "admin-$suffix@example.test"
        val memberEmail = "member-$suffix@example.test"

        repository.create(
            NewAuthUser(
                email = adminEmail,
                password = "correct horse battery staple",
                role = AuthRole.Admin,
                emailConfirmed = true,
            ),
        )
        repository.create(
            NewAuthUser(
                email = memberEmail,
                password = "member password",
                role = AuthRole.Member,
                emailConfirmed = true,
            ),
        )

        environment {
            config = postgresConfig()
        }
        application {
            module()
        }

        val anonymous = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/admin/proof").status)

        val member = createClient { install(HttpCookies) }
        member.login(memberEmail, "member password")
        assertEquals(HttpStatusCode.Forbidden, member.get("/admin/proof").status)

        val admin = createClient { install(HttpCookies) }
        val csrfToken = admin.login(adminEmail, "correct horse battery staple")
        val proof = admin.get("/admin/proof")
        assertEquals(HttpStatusCode.OK, proof.status)
        assertEquals("admin:$adminEmail", proof.bodyAsText())

        assertEquals(HttpStatusCode.Forbidden, admin.post("/admin/proof").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            admin.post("/admin/proof") {
                header(AuthRoutes.CSRF_HEADER, "wrong-token")
            }.status,
        )

        val changed =
            admin.post("/admin/proof") {
                header(AuthRoutes.CSRF_HEADER, csrfToken)
            }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals("changed:$adminEmail", changed.bodyAsText())

        assertEquals(HttpStatusCode.OK, admin.post("/auth/logout").status)
        assertEquals(HttpStatusCode.Unauthorized, admin.get("/admin/proof").status)
    }

    private fun authRepository(): AuthRepository {
        val factory = DatabaseFactory(postgresSettings(poolName = "voenix-shop-auth-test"))
        databaseFactory = factory
        return AuthRepository(factory.connectAndMigrate())
    }

    private suspend fun HttpClient.login(
        email: String,
        password: String,
    ): String {
        val response =
            post("/auth/login") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("email", email)
                            append("password", password)
                        },
                    ),
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        return assertNotNull(response.headers[AuthRoutes.CSRF_HEADER])
    }
}

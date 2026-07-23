package shop.voenix.email

import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.rendering.RenderedEmail

internal class EmailModuleTest {
    @Test
    fun `runtime handle exposes narrow capabilities and closes delivery on stop`() {
        val delivery = CloseableDelivery()
        val database =
            Database.connect(
                "jdbc:postgresql://localhost/not-used",
                driver = "org.postgresql.Driver",
            )
        val module =
            createEmailModule(
                database = database,
                settings = EmailSettings(),
                source = { null },
                delivery = delivery,
                closeableDelivery = delivery,
            )
        assertSame(module.userEmails as Any, module.outbox as Any)

        testApplication {
            application {
                module.install(this)
                assertFailsWith<IllegalStateException> { module.install(this) }
            }
        }

        assertTrue(delivery.closed.get())
    }

    private class CloseableDelivery : EmailDelivery, AutoCloseable {
        val closed: AtomicBoolean = AtomicBoolean()

        override suspend fun deliver(
            email: RenderedEmail,
            campaignId: String?,
        ): EmailDeliveryResult = EmailDeliveryResult.Accepted

        override fun close() {
            closed.set(true)
        }
    }
}

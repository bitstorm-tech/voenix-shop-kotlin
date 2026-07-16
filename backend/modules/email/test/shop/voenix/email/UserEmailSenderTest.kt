package shop.voenix.email

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.outbox.EmailJobRepository
import shop.voenix.email.rendering.EmailRenderer
import shop.voenix.email.rendering.RenderedEmail
import shop.voenix.email.rendering.UserEmailRenderer

internal class UserEmailSenderTest {
    @Test
    fun `enabled direct send renders and calls delivery exactly once without retry`() =
        runBlocking {
            val delivery = RecordingDelivery(EmailDeliveryResult.Accepted)
            val sender = service(enabled = true, delivery = delivery)

            sender.send(UserEmail.PasswordChangedNotification(EmailRecipient("kunde@example.com")))

            assertEquals(1, delivery.calls)
            assertEquals(null, delivery.campaignId)
        }

    @Test
    fun `disabled direct send returns before rendering and provider access`() = runBlocking {
        val delivery = RecordingDelivery(EmailDeliveryResult.Accepted)
        val sender =
            service(
                enabled = false,
                delivery = delivery,
                renderer = UserEmailRenderer { error("Renderer must not be called") },
            )

        sender.send(UserEmail.PasswordChangedNotification(EmailRecipient("kunde@example.com")))

        assertEquals(0, delivery.calls)
    }

    @Test
    fun `delivery failure becomes secret free public exception`() = runBlocking {
        val sender =
            service(
                enabled = true,
                delivery = RecordingDelivery(EmailDeliveryResult.Failed("PROVIDER_HTTP_500")),
            )

        val exception =
            assertFailsWith<EmailDeliveryException> {
                sender.send(
                    UserEmail.AccountConfirmation(
                        EmailRecipient("secret-recipient@example.com"),
                        EmailActionUrl("https://shop.example/confirm?token=secret"),
                    )
                )
            }
        val message = exception.message.orEmpty()
        kotlin.test.assertFalse(message.contains("recipient"))
        kotlin.test.assertFalse(message.contains("token"))
        kotlin.test.assertFalse(message.contains("PROVIDER_HTTP_500"))
    }

    @Test
    fun `caller cancellation propagates`() = runBlocking {
        val sender =
            service(
                enabled = true,
                delivery = EmailDelivery { _, _ -> throw CancellationException("cancel") },
            )

        assertFailsWith<CancellationException> {
            sender.send(UserEmail.PasswordChangedNotification(EmailRecipient("kunde@example.com")))
        }
        Unit
    }

    private fun service(
        enabled: Boolean,
        delivery: EmailDelivery,
        renderer: UserEmailRenderer = EmailRenderer(),
    ): UserEmailSender {
        val settings =
            EmailSettings(
                enabled = enabled,
                apiKey = if (enabled) "key" else "",
                fromEmail = if (enabled) "mail@voenix.shop" else "",
            )
        val database =
            Database.connect(
                "jdbc:postgresql://localhost/not-used",
                driver = "org.postgresql.Driver",
            )
        return EmailService(
            settings,
            renderer,
            delivery,
            EmailJobRepository(database),
        )
    }

    private class RecordingDelivery(private val result: EmailDeliveryResult) : EmailDelivery {
        var calls: Int = 0
        var campaignId: String? = null

        override suspend fun deliver(
            email: RenderedEmail,
            campaignId: String?,
        ): EmailDeliveryResult {
            calls += 1
            this.campaignId = campaignId
            return result
        }
    }
}

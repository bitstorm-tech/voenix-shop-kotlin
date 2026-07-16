package shop.voenix.email

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.outbox.EmailJobRepository
import shop.voenix.email.rendering.EmailRenderer

internal class EmailService(
    private val settings: EmailSettings,
    private val renderer: EmailRenderer,
    private val delivery: EmailDelivery,
    private val repository: EmailJobRepository,
) : UserEmailSender, EmailOutbox {
    override suspend fun send(email: UserEmail) {
        if (!settings.enabled) return
        val rendered = renderer.render(email)
        when (delivery.deliver(rendered, campaignId = null)) {
            EmailDeliveryResult.Accepted -> Unit
            is EmailDeliveryResult.Failed -> throw EmailDeliveryException()
        }
    }

    override suspend fun enqueue(
        idempotencyKey: String,
        reference: QueuedEmailReference,
    ): Long {
        val normalizedKey = idempotencyKey.normalizedIdempotencyKey()
        return repository.enqueueInCurrentTransaction(
            idempotencyHash =
                digest(IDEMPOTENCY_DOMAIN, normalizedKey.toByteArray(StandardCharsets.UTF_8)),
            intentHash = digest(INTENT_DOMAIN, reference.intentBytes()),
            reference = reference,
        )
    }

    private fun String.normalizedIdempotencyKey(): String {
        val normalized = trim()
        require(normalized.length in 1..MAX_IDEMPOTENCY_KEY_LENGTH) {
            "Email idempotency key must contain between 1 and 200 characters"
        }
        require(normalized.matches(IDEMPOTENCY_KEY_PATTERN)) {
            "Email idempotency key must be a namespaced, versioned, non-secret business identifier"
        }
        return normalized
    }

    private fun QueuedEmailReference.intentBytes(): ByteArray {
        val kind =
            when (this) {
                is QueuedEmailReference.OrderConfirmation -> "ORDER_CONFIRMATION"
                is QueuedEmailReference.ProducerPdfNotification -> "PRODUCER_PDF_NOTIFICATION"
            }
        val kindBytes = kind.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(Int.SIZE_BYTES + kindBytes.size + Long.SIZE_BYTES)
            .putInt(kindBytes.size)
            .put(kindBytes)
            .putLong(sourceId)
            .array()
    }

    private fun digest(domain: ByteArray, value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(domain)
            digest(value)
        }

    private companion object {
        val IDEMPOTENCY_DOMAIN: ByteArray =
            "email-idempotency:v1\u0000".toByteArray(StandardCharsets.UTF_8)
        val INTENT_DOMAIN: ByteArray = "email-intent:v1\u0000".toByteArray(StandardCharsets.UTF_8)
        val IDEMPOTENCY_KEY_PATTERN: Regex =
            Regex("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*:v[1-9][0-9]*:[A-Za-z0-9._:-]+")
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
    }
}

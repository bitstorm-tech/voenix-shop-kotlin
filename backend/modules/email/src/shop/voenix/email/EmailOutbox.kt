package shop.voenix.email

public fun interface EmailOutbox {
    public suspend fun enqueue(
        idempotencyKey: String,
        reference: QueuedEmailReference,
    ): Long
}

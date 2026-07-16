package shop.voenix.email

public fun interface EmailOutbox {
    public suspend fun enqueue(reference: QueuedEmailReference): Long
}

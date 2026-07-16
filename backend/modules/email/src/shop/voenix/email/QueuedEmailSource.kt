package shop.voenix.email

public fun interface QueuedEmailSource {
    public suspend fun resolve(reference: QueuedEmailReference): QueuedEmail?
}

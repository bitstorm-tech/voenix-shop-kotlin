package shop.voenix.email.outbox

internal enum class EmailJobStatus {
    PENDING,
    PROCESSING,
    TRANSMITTED,
}

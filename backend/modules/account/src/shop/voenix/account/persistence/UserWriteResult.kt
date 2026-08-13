package shop.voenix.account.persistence

/**
 * Persistence outcomes of the writes guarded by the case-insensitive unique e-mail index. The index
 * — not a preliminary lookup — is the concurrency-safe authority: SQL state 23505 maps to
 * [EmailTaken] via `executePostgresWrite`. [InvalidLink] is produced only by the token-consuming
 * e-mail change confirmation, [UnknownSupplier] only by an insert that carries a supplier link.
 */
internal sealed interface UserWriteResult {
    data class Stored(val id: Long) : UserWriteResult

    data object EmailTaken : UserWriteResult

    data object InvalidLink : UserWriteResult

    /**
     * The `users.supplier_id` foreign key refused the insert (SQL state 23503): the supplier does
     * not exist. Like the unique e-mail index, the constraint is the authority — no preliminary
     * existence query could answer this without a race.
     */
    data object UnknownSupplier : UserWriteResult
}

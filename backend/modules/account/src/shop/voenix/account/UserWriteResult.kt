package shop.voenix.account

/**
 * Persistence outcomes of the writes guarded by the case-insensitive unique e-mail index. The index
 * — not a preliminary lookup — is the concurrency-safe authority: SQL state 23505 maps to
 * [EmailTaken] via `executePostgresWrite`. [InvalidLink] is produced only by the token-consuming
 * e-mail change confirmation.
 */
internal sealed interface UserWriteResult {
    data class Stored(val id: Long) : UserWriteResult

    data object EmailTaken : UserWriteResult

    data object InvalidLink : UserWriteResult
}

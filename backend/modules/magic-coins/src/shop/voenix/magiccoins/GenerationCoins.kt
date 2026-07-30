package shop.voenix.magiccoins

import shop.voenix.operation.OperationResult

/**
 * The one capability the magic-coins module exports: what a module that runs a paid AI image
 * generation may do with a visitor's coin balance.
 *
 * The capability deliberately has two separate methods instead of one combined check-and-spend. The
 * expensive external generation call sits between the two, so combining them would either pull the
 * image provider into this module or hold a database transaction open across a long network call.
 * Exact accounting under concurrency would need a reserve/commit model, which is out of scope (see
 * `generator-migration.md`).
 *
 * Reading a balance is not part of the capability. It stays internal, because the module owns the
 * only endpoint that reports it.
 */
public interface GenerationCoins {
    /**
     * Whether [owner] can currently afford one generation.
     *
     * The result is an [OperationResult] on purpose: an infrastructure failure must never reach the
     * caller as "no balance", because that would answer a broken database with "not enough Magic
     * Coins" and charge the customer for a defect that is not theirs.
     */
    public suspend fun hasEnoughForGeneration(owner: MagicCoinsOwner): OperationResult<Boolean>

    /**
     * Deducts the cost of one generation from [owner]'s balance and reports whether the deduction
     * happened. The deduction is a single atomic statement, so a balance can never go negative and
     * two concurrent spends of the last coin let exactly one of them win.
     *
     * The answer is a plain `Boolean` because a caller can do exactly one thing about any negative
     * outcome — an insufficient balance and a database failure alike: log it and keep the already
     * produced result. A richer failure type would be a distinction without a consequence; the
     * reason is logged with the owner context inside this module.
     */
    public suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean
}

package shop.voenix.magiccoins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

internal class MagicCoinsRepository(private val database: Database) {
    internal suspend fun getOrCreateBalance(
        owner: MagicCoinsOwner,
        initialBalance: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                MagicCoins.insertIgnore { statement ->
                    when (owner) {
                        is MagicCoinsOwner.User -> statement[userId] = owner.id
                        is MagicCoinsOwner.Guest -> statement[guestSessionToken] = owner.token
                    }
                    statement[balance] = initialBalance
                }
                MagicCoins.select(MagicCoins.balance)
                    .where(ownerPredicate(owner))
                    .single()[MagicCoins.balance]
            }
        }

    internal suspend fun spend(
        owner: MagicCoinsOwner,
        cost: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                MagicCoins.update({
                    ownerPredicate(owner) and (MagicCoins.balance greaterEq cost)
                }) { statement ->
                    statement[MagicCoins.balance] = MagicCoins.balance - cost
                    statement[MagicCoins.updatedAt] = CurrentTimestampWithTimeZone
                }
            }
        }

    private fun ownerPredicate(owner: MagicCoinsOwner): Op<Boolean> =
        when (owner) {
            is MagicCoinsOwner.User -> MagicCoins.userId eq owner.id
            is MagicCoinsOwner.Guest -> MagicCoins.guestSessionToken eq owner.token
        }
}

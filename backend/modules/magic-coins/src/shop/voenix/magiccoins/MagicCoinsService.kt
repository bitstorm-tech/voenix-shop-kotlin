package shop.voenix.magiccoins

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class MagicCoinsService(private val repository: MagicCoinsRepository) :
    MagicCoinsOperations {
    override suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int> =
        logger.databaseOperation(
            "Magic Coins balance unavailable for ${owner.logDescription}",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.getOrCreateBalance(owner, INITIAL_BALANCE))
        }

    override suspend fun hasEnoughForGeneration(owner: MagicCoinsOwner): OperationResult<Boolean> =
        when (val result = balance(owner)) {
            is OperationResult.Success -> OperationResult.Success(result.value >= GENERATION_COST)
            else -> OperationResult.UnexpectedFailure
        }

    override suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean =
        logger.databaseOperation(
            "Magic Coin spend failed for ${owner.logDescription}",
            false,
        ) {
            repository.getOrCreateBalance(owner, INITIAL_BALANCE)
            if (repository.spend(owner, GENERATION_COST) > 0) {
                true
            } else {
                logger.warn(
                    "Magic Coin spend skipped for {}: insufficient balance at spend time",
                    owner.logDescription,
                )
                false
            }
        }

    private companion object {
        const val INITIAL_BALANCE = 10
        const val GENERATION_COST = 1

        val logger: Logger = LoggerFactory.getLogger(MagicCoinsService::class.java)
    }
}

/**
 * Everything the module's own routes need: the exported [GenerationCoins] capability plus reading a
 * balance. The seam stays internal — another module receives the capability, never this interface,
 * because the balance endpoint belongs to magic-coins alone.
 */
internal interface MagicCoinsOperations : GenerationCoins {
    suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int>
}

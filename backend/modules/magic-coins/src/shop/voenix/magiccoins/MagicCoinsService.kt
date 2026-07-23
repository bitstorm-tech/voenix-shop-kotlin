package shop.voenix.magiccoins

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class MagicCoinsService(private val repository: MagicCoinsRepository) :
    MagicCoinsOperations {
    override suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int> =
        withFailureFallback(
            onFailure = { failure ->
                logger.error("Magic Coins balance unavailable for ${owner.logDescription}", failure)
                OperationResult.UnexpectedFailure
            }
        ) {
            OperationResult.Success(repository.getOrCreateBalance(owner, INITIAL_BALANCE))
        }

    override suspend fun hasEnoughForGeneration(owner: MagicCoinsOwner): OperationResult<Boolean> =
        when (val result = balance(owner)) {
            is OperationResult.Success -> OperationResult.Success(result.value >= GENERATION_COST)
            else -> OperationResult.UnexpectedFailure
        }

    override suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean =
        withFailureFallback(
            onFailure = { failure ->
                logger.error("Magic Coin spend failed for ${owner.logDescription}", failure)
                false
            }
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

    private suspend fun <T> withFailureFallback(
        onFailure: (Exception) -> T,
        block: suspend () -> T,
    ): T = runCatching {
        block()
    }
        .getOrElse { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            onFailure(failure)
        }

    private companion object {
        const val INITIAL_BALANCE = 10
        const val GENERATION_COST = 1

        val logger: Logger = LoggerFactory.getLogger(MagicCoinsService::class.java)
    }
}

package shop.voenix.magiccoins

import shop.voenix.operation.OperationResult

internal interface MagicCoinsOperations {
    suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int>

    suspend fun hasEnoughForGeneration(owner: MagicCoinsOwner): OperationResult<Boolean>

    suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean
}

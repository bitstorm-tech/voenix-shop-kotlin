package shop.voenix.magiccoins

import shop.voenix.operation.OperationResult

/**
 * Everything the module's own routes need: the exported [GenerationCoins] capability plus reading a
 * balance. The seam stays internal — another module receives the capability, never this interface,
 * because the balance endpoint belongs to magic-coins alone.
 */
internal interface MagicCoinsOperations : GenerationCoins {
    suspend fun balance(owner: MagicCoinsOwner): OperationResult<Int>
}

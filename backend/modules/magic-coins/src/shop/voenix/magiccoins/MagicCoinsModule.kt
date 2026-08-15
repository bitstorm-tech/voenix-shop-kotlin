package shop.voenix.magiccoins

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.GuestTokens

internal class MagicCoinsModule internal constructor(internal val operations: MagicCoinsOperations)

internal fun createMagicCoinsModule(database: Database): MagicCoinsModule =
    MagicCoinsModule(MagicCoinsService(MagicCoinsRepository(database)))

/**
 * Installs the module and returns its one exported capability, so the composition root can hand
 * [GenerationCoins] to the module that charges for image generation. Everything else the module
 * owns — the handle, the operations seam, the repository, the table, and the coin amounts — stays
 * internal.
 */
public fun Application.installMagicCoinsModule(
    database: Database,
    guestTokens: GuestTokens,
): GenerationCoins {
    val module = createMagicCoinsModule(database)
    installMagicCoinsRoutes(module.operations, guestTokens)
    return module.operations
}

package shop.voenix.magiccoins

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.GuestTokens

internal class MagicCoinsModule
internal constructor(
    internal val operations: MagicCoinsOperations,
    private val guestTokens: GuestTokens,
) {
    internal fun install(application: Application): Unit =
        MagicCoinsRoutes.install(application, operations, guestTokens)
}

internal fun createMagicCoinsModule(
    database: Database,
    guestTokens: GuestTokens,
): MagicCoinsModule =
    MagicCoinsModule(MagicCoinsService(MagicCoinsRepository(database)), guestTokens)

internal fun Application.installMagicCoinsModule(
    magicCoins: MagicCoinsOperations,
    guestTokens: GuestTokens,
): Unit = MagicCoinsRoutes.install(this, magicCoins, guestTokens)

/**
 * Installs the module and returns its one exported capability, so the composition root can hand
 * [GenerationCoins] to the module that charges for image generation. Everything else the module
 * owns — the handle, the operations seam, the repository, the table, and the coin amounts — stays
 * internal.
 */
public fun Application.installMagicCoinsModule(
    database: Database,
    guestTokens: GuestTokens,
): GenerationCoins =
    createMagicCoinsModule(database, guestTokens).let { module ->
        module.install(this)
        module.operations
    }

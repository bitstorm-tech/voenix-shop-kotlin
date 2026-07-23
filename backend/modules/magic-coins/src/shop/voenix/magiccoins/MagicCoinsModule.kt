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

public fun Application.installMagicCoinsModule(
    database: Database,
    guestTokens: GuestTokens,
): Unit = createMagicCoinsModule(database, guestTokens).install(this)

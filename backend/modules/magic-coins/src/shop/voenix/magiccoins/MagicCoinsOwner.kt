package shop.voenix.magiccoins

import io.ktor.server.application.ApplicationCall
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession

/**
 * Who owns a coin balance: a signed-in customer or a guest identified by the encrypted
 * `voenix.guest` cookie.
 *
 * The type is public because it is the parameter of the exported [GenerationCoins] capability — a
 * consumer has to name the owner it charges. Its two cases carry nothing but that identity, so
 * nothing about how balances are stored leaves this module.
 */
public sealed interface MagicCoinsOwner {
    public data class User(val id: Long) : MagicCoinsOwner

    public data class Guest(val token: String) : MagicCoinsOwner
}

/**
 * The owner of the coin balance behind this request: the signed-in user, or else the guest of the
 * `voenix.guest` cookie, which is created when the request does not carry a usable one yet.
 *
 * This is the one implementation of that rule. Every module that charges coins resolves its owner
 * through it, so a session user id that is not a positive number falls back to the guest path
 * everywhere, and no caller can invent a second, slightly different rule.
 */
public fun ApplicationCall.magicCoinsOwner(guestTokens: GuestTokens): MagicCoinsOwner {
    val userId = currentUserSession()?.userId?.toLongOrNull()?.takeIf { it > 0 }
    return if (userId != null) {
        MagicCoinsOwner.User(userId)
    } else {
        MagicCoinsOwner.Guest(guestTokens.getOrCreate(this))
    }
}

/**
 * How an owner appears in a log line. It is internal because only this module logs about balances:
 * a guest token identifies a visitor's session and is therefore never written to a log, a user id
 * is.
 */
internal val MagicCoinsOwner.logDescription: String
    get() =
        when (this) {
            is MagicCoinsOwner.User -> "user owner $id"
            is MagicCoinsOwner.Guest -> "guest owner"
        }

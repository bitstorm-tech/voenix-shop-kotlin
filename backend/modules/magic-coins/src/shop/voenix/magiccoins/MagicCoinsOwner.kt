package shop.voenix.magiccoins

internal sealed interface MagicCoinsOwner {
    data class User(val id: Long) : MagicCoinsOwner

    data class Guest(val token: String) : MagicCoinsOwner

    val logDescription: String
        get() =
            when (this) {
                is User -> "user owner $id"
                is Guest -> "guest owner"
            }
}

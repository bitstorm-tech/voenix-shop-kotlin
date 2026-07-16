package shop.voenix.email

public fun interface UserEmailSender {
    public suspend fun send(email: UserEmail)
}

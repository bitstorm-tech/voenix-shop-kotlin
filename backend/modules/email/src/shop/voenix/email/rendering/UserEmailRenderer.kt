package shop.voenix.email.rendering

import shop.voenix.email.UserEmail

internal fun interface UserEmailRenderer {
    fun render(email: UserEmail): RenderedEmail
}

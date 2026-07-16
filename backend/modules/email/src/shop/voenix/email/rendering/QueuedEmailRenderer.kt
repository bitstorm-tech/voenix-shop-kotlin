package shop.voenix.email.rendering

import shop.voenix.email.QueuedEmail

internal fun interface QueuedEmailRenderer {
    fun render(email: QueuedEmail): RenderedEmail
}

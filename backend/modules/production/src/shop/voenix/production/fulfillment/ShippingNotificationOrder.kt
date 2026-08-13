package shop.voenix.production.fulfillment

import shop.voenix.email.EmailActionUrl

/**
 * The three things the order module knows and the shipping mail needs: whom to write to, how to
 * greet them, and the permanent link to their order.
 *
 * [orderUrl] arrives ready-built as an [EmailActionUrl]. That is the whole point of the shape: the
 * order's access token is a bearer credential, so it never crosses this boundary — the order module
 * builds the link from it and hands over the result, which redacts itself in every `toString`.
 *
 * There is no address, no amount, and no item list here: production reads what it shipped from its
 * own snapshot, and money is the confirmation mail's business.
 */
public data class ShippingNotificationOrder(
    public val recipientEmail: String,
    public val customerFirstName: String,
    public val orderUrl: EmailActionUrl,
)

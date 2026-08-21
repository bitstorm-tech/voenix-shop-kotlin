package shop.voenix.email

import java.time.LocalDate

public sealed interface QueuedEmail {
    public val recipient: EmailRecipient

    /**
     * [orderUrl] is the permanent link to this one order, and it is an [EmailActionUrl] on purpose:
     * a data class prints every field in `toString`, and this one is a bearer credential. The value
     * type redacts itself, so the link cannot reach a log line through a trace of the mail.
     */
    public data class OrderConfirmation(
        override val recipient: EmailRecipient,
        public val orderId: Long,
        public val orderDate: LocalDate,
        public val orderUrl: EmailActionUrl,
        public val customerFirstName: String,
        public val shippingAddress: Address,
        public val billingAddress: Address,
        public val items: List<Item>,
        public val subtotalInCents: Long,
        public val shippingCostInCents: Long,
        public val discountInCents: Long,
        public val totalInCents: Long,
    ) : QueuedEmail {
        init {
            require(orderId > 0) { "Order ID must be positive" }
            requireSafeDisplayValue(customerFirstName, "Customer first name")
            require(items.isNotEmpty()) { "Order confirmation must contain at least one item" }
            require(subtotalInCents >= 0) { "Subtotal must not be negative" }
            require(shippingCostInCents >= 0) { "Shipping cost must not be negative" }
            require(discountInCents >= 0) { "Discount must not be negative" }
            require(totalInCents >= 0) { "Order total must not be negative" }
            val expectedTotal =
                Math.subtractExact(
                    Math.addExact(subtotalInCents, shippingCostInCents),
                    discountInCents,
                )
            require(totalInCents == expectedTotal) {
                "Order total must equal subtotal plus shipping cost minus discount"
            }
        }

        public data class Address(
            public val firstName: String,
            public val lastName: String,
            public val street: String,
            public val houseNumber: String,
            public val city: String,
            public val postalCode: String,
            public val country: String,
        ) {
            init {
                listOf(
                        "First name" to firstName,
                        "Last name" to lastName,
                        "Street" to street,
                        "House number" to houseNumber,
                        "City" to city,
                        "Postal code" to postalCode,
                        "Country" to country,
                    )
                    .forEach { (label, value) -> requireSafeDisplayValue(value, label) }
            }
        }

        public data class Item(
            public val articleName: String,
            public val variantName: String,
            public val quantity: Int,
            public val unitPriceInCents: Long,
        ) {
            init {
                requireSafeDisplayValue(articleName, "Article name")
                requireSafeDisplayValue(variantName, "Variant name")
                require(quantity > 0) { "Item quantity must be positive" }
                require(unitPriceInCents >= 0) { "Item price must not be negative" }
                Math.multiplyExact(unitPriceInCents, quantity.toLong())
            }
        }
    }

    public data class ProducerPdfNotification(
        override val recipient: EmailRecipient,
        public val orderId: Long,
        public val fileName: String,
        public val destinationLabel: String,
        public val orderDate: LocalDate,
        public val itemCount: Int,
        public val producerName: String? = null,
    ) : QueuedEmail {
        init {
            require(orderId > 0) { "Order ID must be positive" }
            requireSafeDisplayValue(fileName, "File name")
            requireSafeDisplayValue(destinationLabel, "Destination label")
            producerName?.let { requireSafeDisplayValue(it, "Producer name") }
            require(itemCount > 0) { "Item count must be positive" }
        }
    }

    /**
     * One package of an order is on its way to the customer.
     *
     * There is no price, no total, and no address in this type, and that is the point: the mail
     * reports a shipment, and the money side of the order is already in the confirmation mail. The
     * customer sees what is in *this* package — an order can ship in several — plus the tracking
     * link when the carrier is one the shop can build a link for.
     *
     * [orderUrl] is the same permanent order link the confirmation carries, and an [EmailActionUrl]
     * for the same reason: a data class prints every field in `toString`, and this one is a bearer
     * credential. [trackingUrl] is built by the shop from [carrierName]'s bounded carrier, never
     * accepted from a caller, so no mail sent under the shop's name can carry a link somebody else
     * chose.
     */
    public data class ShippingNotification(
        override val recipient: EmailRecipient,
        public val orderId: Long,
        public val customerFirstName: String,
        public val items: List<Item>,
        public val orderUrl: EmailActionUrl,
        public val carrierName: String? = null,
        public val trackingNumber: String? = null,
        public val trackingUrl: EmailActionUrl? = null,
    ) : QueuedEmail {
        init {
            require(orderId > 0) { "Order ID must be positive" }
            requireSafeDisplayValue(customerFirstName, "Customer first name")
            require(items.isNotEmpty()) { "Shipping notification must contain at least one item" }
            carrierName?.let { requireSafeDisplayValue(it, "Carrier name") }
            trackingNumber?.let { requireSafeDisplayValue(it, "Tracking number") }
            require(trackingUrl == null || trackingNumber != null) {
                "A tracking link without a tracking number cannot be shown"
            }
        }

        /** One shipped line: what it is and how many of it — never what it cost. */
        public data class Item(
            public val articleName: String,
            public val variantName: String,
            public val quantity: Int,
        ) {
            init {
                requireSafeDisplayValue(articleName, "Article name")
                requireSafeDisplayValue(variantName, "Variant name")
                require(quantity > 0) { "Item quantity must be positive" }
            }
        }
    }

    /**
     * One print-on-demand job needs a human: the partner cancelled its order, flagged it as needing
     * action, or this backend does not know whether an order was created at all.
     *
     * The mail goes to the shop's own operations address, never to a customer, and it carries no
     * customer data: an operator opens the partner's backoffice with the two numbers and the
     * partner's order id, which is all this alert is for.
     *
     * [reason] is an enum rather than a string on purpose. Everything else in this type is a number
     * or the partner's own order id, so no provider text can travel into a mail — the reason is the
     * one field a webhook could otherwise fill, and it is bounded here instead.
     */
    public data class SpodOpsAlert(
        override val recipient: EmailRecipient,
        public val jobId: Long,
        public val orderId: Long,
        public val reason: Reason,
        public val externalReference: String? = null,
    ) : QueuedEmail {
        init {
            require(jobId > 0) { "Production job ID must be positive" }
            require(orderId > 0) { "Order ID must be positive" }
            externalReference?.let { requireSafeDisplayValue(it, "External reference") }
        }

        /** Why an operator has to look at this job. */
        public enum class Reason {
            /** The partner cancelled the order. */
            CANCELLED,

            /** The partner flagged the order as needing action. */
            NEEDS_ACTION,

            /** Two ambiguous creations in a row: the job is quarantined until a human decides. */
            OUTCOME_UNKNOWN,
        }
    }
}

private fun requireSafeDisplayValue(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= MAX_DISPLAY_VALUE_LENGTH) {
        "$label must contain at most 255 characters"
    }
    require(value.none { it.isISOControl() }) { "$label must not contain control characters" }
}

private const val MAX_DISPLAY_VALUE_LENGTH = 255

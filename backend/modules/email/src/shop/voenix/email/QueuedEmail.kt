package shop.voenix.email

import java.time.LocalDate

public sealed interface QueuedEmail {
    public val recipient: EmailRecipient

    public data class OrderConfirmation(
        override val recipient: EmailRecipient,
        public val orderId: Long,
        public val orderDate: LocalDate,
        public val customerFirstName: String,
        public val shippingAddress: Address,
        public val billingAddress: Address,
        public val items: List<Item>,
        public val shippingCostInCents: Long,
        public val totalInCents: Long,
    ) : QueuedEmail {
        init {
            require(orderId > 0) { "Order ID must be positive" }
            requireSafeDisplayValue(customerFirstName, "Customer first name")
            require(items.isNotEmpty()) { "Order confirmation must contain at least one item" }
            require(shippingCostInCents >= 0) { "Shipping cost must not be negative" }
            require(totalInCents >= shippingCostInCents) {
                "Order total must not be smaller than shipping cost"
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
}

private fun requireSafeDisplayValue(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= MAX_DISPLAY_VALUE_LENGTH) {
        "$label must contain at most 255 characters"
    }
    require(value.none { it.isISOControl() }) { "$label must not contain control characters" }
}

private const val MAX_DISPLAY_VALUE_LENGTH = 255

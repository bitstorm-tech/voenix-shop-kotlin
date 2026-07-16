package shop.voenix.email.template

import kotlinx.html.FlowContent
import kotlinx.html.TABLE
import kotlinx.html.br
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.tr
import shop.voenix.email.QueuedEmail
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail
import shop.voenix.email.template.TextEmailLayout.separator

internal object OrderConfirmationEmailTemplate {
    fun subject(orderId: Long): String = "Bestellbestätigung #$orderId"

    fun renderHtml(content: Content): String =
        renderHtmlEmail(
            footer = "Bei Fragen zu deiner Bestellung erreichst du uns jederzeit per E-Mail."
        ) {
            contentSection {
                h2 { +"Bestellbestätigung" }
                p {
                    +"Hallo ${content.customerFirstName}, vielen Dank für deine Bestellung! "
                    +"Hier ist deine Zusammenfassung:"
                }
                p {
                    +"Sobald deine Bestellung versendet wurde, erhältst du eine "
                    +"Versandbestätigung per E-Mail."
                }
            }
            orderMetadata(content)
            orderItems(content)
            orderTotals(content)
            orderAddresses(content)
        }

    fun renderText(content: Content): String =
        renderTextEmail(subject(content.orderId)) {
            appendLine("Hallo ${content.customerFirstName},")
            appendLine("vielen Dank für deine Bestellung!")
            appendLine(
                "Sobald deine Bestellung versendet wurde, erhältst du eine Versandbestätigung per E-Mail."
            )
            appendLine()
            appendLine("Bestellnummer: #${content.orderId}")
            appendLine("Bestelldatum:  ${content.orderDate}")
            appendLine()
            appendLine("Artikel:")
            separator()
            content.items.forEach { item ->
                appendLine("  ${item.articleName} (${item.variantName})")
                appendLine("    ${item.quantity}x ${item.unitPrice} = ${item.totalPrice}")
            }
            separator()
            appendLine("Zwischensumme: ${content.subtotal}")
            appendLine("Versand:       ${content.shippingCost}")
            appendLine("Gesamtbetrag:  ${content.total}")
            appendLine()
            appendLine("Lieferadresse:")
            appendAddress(content.shippingAddress)
            appendLine()
            appendLine("Rechnungsadresse:")
            appendAddress(content.billingAddress)
        }

    private fun TABLE.orderMetadata(content: Content) {
        tr {
            td {
                style = "padding:8px 32px 16px"
                p {
                    +"Bestellnummer: "
                    strong { +"#${content.orderId}" }
                    br
                    +"Bestelldatum: "
                    strong { +content.orderDate }
                }
            }
        }
    }

    private fun TABLE.orderItems(content: Content) {
        tr {
            td {
                style = "padding:8px 32px 16px"
                table {
                    attributes["role"] = "presentation"
                    style = "width:100%;border-collapse:collapse"
                    content.items.forEach { item ->
                        tr {
                            td {
                                +item.articleName
                                br
                                small { +item.variantName }
                            }
                            td {
                                style = "text-align:center"
                                +item.quantity.toString()
                            }
                            td {
                                style = "text-align:right"
                                +item.totalPrice
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TABLE.orderTotals(content: Content) {
        tr {
            td {
                style = "padding:0 32px 16px"
                p {
                    +"Zwischensumme: ${content.subtotal}"
                    br
                    +"Versand: ${content.shippingCost}"
                    br
                    strong { +"Gesamtbetrag: ${content.total}" }
                }
            }
        }
    }

    private fun TABLE.orderAddresses(content: Content) {
        tr {
            td {
                style = "padding:8px 32px 24px"
                address("Lieferadresse", content.shippingAddress)
                address("Rechnungsadresse", content.billingAddress)
            }
        }
    }

    private fun FlowContent.address(
        label: String,
        address: QueuedEmail.OrderConfirmation.Address,
    ) {
        p {
            strong { +label }
            br
            +"${address.firstName} ${address.lastName}"
            br
            +"${address.street} ${address.houseNumber}"
            br
            +"${address.postalCode} ${address.city}"
            br
            +address.country
        }
    }

    private fun StringBuilder.appendAddress(address: QueuedEmail.OrderConfirmation.Address) {
        appendLine("  ${address.firstName} ${address.lastName}")
        appendLine("  ${address.street} ${address.houseNumber}")
        appendLine("  ${address.postalCode} ${address.city}")
        appendLine("  ${address.country}")
    }

    data class Content(
        val orderId: Long,
        val orderDate: String,
        val customerFirstName: String,
        val items: List<Item>,
        val subtotal: String,
        val shippingCost: String,
        val total: String,
        val shippingAddress: QueuedEmail.OrderConfirmation.Address,
        val billingAddress: QueuedEmail.OrderConfirmation.Address,
    ) {
        data class Item(
            val articleName: String,
            val variantName: String,
            val quantity: Int,
            val unitPrice: String,
            val totalPrice: String,
        )
    }
}

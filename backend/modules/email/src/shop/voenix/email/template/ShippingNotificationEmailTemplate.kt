package shop.voenix.email.template

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
import shop.voenix.email.template.HtmlEmailLayout.actionSection
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.explanationSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail
import shop.voenix.email.template.TextEmailLayout.separator

/**
 * The mail the customer receives when **one package** of their order leaves a supplier.
 *
 * Two things are deliberate in every sentence here. The mail is supplier-neutral: the customer
 * ordered from the shop and never learns which workshop packed the box. And it never says the order
 * is complete — an order can be split across suppliers, so a second package may follow, and the
 * copy says exactly that instead of promising an end.
 *
 * There is no price anywhere, on purpose: what the order cost is the confirmation mail's job, and a
 * shipment notice that repeats the money only creates a second document to keep consistent.
 *
 * The tracking link is not in this file's hands: the shop builds it from its own bounded carrier
 * list, and the template renders a button only when it got one. Without a link the number is still
 * shown as text, which is all a customer needs to paste it into the carrier's own page.
 */
internal object ShippingNotificationEmailTemplate {
    fun subject(orderId: Long): String = "Dein Paket ist unterwegs – Bestellung ORD-$orderId"

    fun renderHtml(content: Content): String =
        renderHtmlEmail(
            footer = "Bei Fragen zu deiner Bestellung erreichst du uns jederzeit per E-Mail."
        ) {
            contentSection {
                h2 { +"Dein Paket ist unterwegs" }
                p {
                    +"Hallo ${content.customerFirstName}, "
                    +"ein Paket deiner Bestellung ORD-${content.orderId} ist unterwegs."
                }
                p { +SPLIT_SHIPMENT_HINT }
            }
            shippedItems(content)
            trackingSection(content)
            actionSection(content.orderUrl, "Bestellung ansehen")
            explanationSection(
                actionUrl = content.orderUrl,
                validity = OrderConfirmationEmailTemplate.DURABLE_LINK_HINT,
            )
        }

    fun renderText(content: Content): String =
        renderTextEmail(subject(content.orderId)) {
            appendLine("Hallo ${content.customerFirstName},")
            appendLine("ein Paket deiner Bestellung ORD-${content.orderId} ist unterwegs.")
            appendLine(SPLIT_SHIPMENT_HINT)
            appendLine()
            appendLine("Inhalt dieses Pakets:")
            separator()
            content.items.forEach { item ->
                appendLine("  ${item.quantity}x ${item.articleName} (${item.variantName})")
            }
            separator()
            content.carrierName?.let { carrier -> appendLine("Versanddienstleister: $carrier") }
            content.trackingNumber?.let { number -> appendLine("Sendungsnummer: $number") }
            content.trackingUrl?.let { url ->
                appendLine()
                appendLine("Sendung verfolgen:")
                appendLine(url)
            }
            appendLine()
            appendLine("Deine Bestellung ansehen:")
            appendLine()
            appendLine(content.orderUrl)
            appendLine()
            appendLine(OrderConfirmationEmailTemplate.DURABLE_LINK_HINT)
        }

    /** The lines of *this* package, with quantities and without a single amount. */
    private fun TABLE.shippedItems(content: Content) {
        tr {
            td {
                style = "padding:8px 32px 16px"
                p { strong { +"Inhalt dieses Pakets" } }
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
                                style = "text-align:right"
                                +"${item.quantity}x"
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Carrier and tracking number as text, plus the tracking button when the shop could build a
     * link for that carrier. A shipment without any tracking data renders nothing here.
     */
    private fun TABLE.trackingSection(content: Content) {
        if (content.carrierName == null && content.trackingNumber == null) return
        tr {
            td {
                style = "padding:0 32px 16px"
                p {
                    content.carrierName?.let { carrier ->
                        +"Versanddienstleister: "
                        strong { +carrier }
                        br
                    }
                    content.trackingNumber?.let { number ->
                        +"Sendungsnummer: "
                        strong { +number }
                    }
                }
            }
        }
        content.trackingUrl?.let { url -> actionSection(url, "Sendung verfolgen") }
    }

    /**
     * Why this mail is not "your order is complete": the order may travel in several packages, and
     * each of them gets its own mail.
     */
    const val SPLIT_SHIPMENT_HINT: String =
        "Deine Bestellung kann in mehreren Paketen ankommen. Für jedes weitere Paket erhältst du " +
            "eine eigene E-Mail."

    data class Content(
        val orderId: Long,
        val customerFirstName: String,
        val items: List<Item>,
        val orderUrl: String,
        val carrierName: String?,
        val trackingNumber: String?,
        val trackingUrl: String?,
    ) {
        data class Item(val articleName: String, val variantName: String, val quantity: Int)
    }
}

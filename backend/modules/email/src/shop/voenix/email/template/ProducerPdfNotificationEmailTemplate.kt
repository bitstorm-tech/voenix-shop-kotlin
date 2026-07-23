package shop.voenix.email.template

import kotlinx.html.FlowContent
import kotlinx.html.br
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.td
import kotlinx.html.tr
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object ProducerPdfNotificationEmailTemplate {
    fun subject(orderId: Long, fileName: String): String = "Neue Bestellung #$orderId – $fileName"

    fun renderHtml(content: Content): String =
        renderHtmlEmail(
            footer = "Bei Rückfragen zur Bestellung wenden Sie sich bitte an den Voenix Shop."
        ) {
            contentSection {
                h2 { +"Neue Bestellung per SFTP übermittelt" }
                p { +content.greeting }
                p { +"Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen." }
            }
            tr {
                td {
                    style = "padding:8px 32px 24px"
                    p {
                        labelledValue("Bestellnummer", "#${content.orderId}")
                        labelledValue("Dateiname", content.fileName)
                        labelledValue("SFTP-Server", content.destinationLabel)
                        labelledValue("Bestelldatum", content.orderDate)
                        +"Anzahl Artikel: "
                        strong { +content.itemCount.toString() }
                    }
                }
            }
        }

    fun renderText(content: Content): String =
        renderTextEmail("Neue Bestellung per SFTP übermittelt") {
            appendLine(content.greeting)
            appendLine("Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen.")
            appendLine()
            appendLine("Bestellnummer:  #${content.orderId}")
            appendLine("Dateiname:      ${content.fileName}")
            appendLine("SFTP-Server:    ${content.destinationLabel}")
            appendLine("Bestelldatum:   ${content.orderDate}")
            appendLine("Anzahl Artikel: ${content.itemCount}")
        }

    private fun FlowContent.labelledValue(label: String, value: String) {
        +"$label: "
        strong { +value }
        br
    }

    data class Content(
        val orderId: Long,
        val fileName: String,
        val destinationLabel: String,
        val orderDate: String,
        val itemCount: Int,
        val greeting: String,
    )
}

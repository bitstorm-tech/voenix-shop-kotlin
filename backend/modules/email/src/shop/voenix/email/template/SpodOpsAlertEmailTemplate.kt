package shop.voenix.email.template

import kotlinx.html.FlowContent
import kotlinx.html.br
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.td
import kotlinx.html.tr
import shop.voenix.email.QueuedEmail
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

/**
 * The operations alert of the print-on-demand channel: one job the automation will not touch again
 * until a human has looked at the partner's backoffice.
 *
 * It is the one mail of this shop that goes to the shop itself, which is why it reads like a work
 * item rather than like a message to a customer: two numbers to look the job up with, the partner's
 * own order id where one exists, and the one sentence that says what to do about it.
 *
 * There is deliberately no customer name, no address, and no link in it. An alert lands in a shared
 * operations mailbox, and everything the recipient needs is in this shop's admin surface behind the
 * job number.
 */
internal object SpodOpsAlertEmailTemplate {
    fun subject(jobId: Long, orderId: Long): String =
        "Aktion nötig: Produktionsauftrag #$jobId (Bestellung ORD-$orderId)"

    fun renderHtml(content: Content): String =
        renderHtmlEmail(footer = FOOTER) {
            contentSection {
                h2 { +"Ein Print-on-Demand-Auftrag braucht eine Entscheidung" }
                p { +content.headline }
                p { +content.instruction }
            }
            tr {
                td {
                    style = "padding:8px 32px 24px"
                    p {
                        labelledValue("Produktionsauftrag", "#${content.jobId}")
                        labelledValue("Bestellnummer", "ORD-${content.orderId}")
                        labelledValue(
                            "SPOD-Auftragsnummer",
                            content.externalReference ?: UNKNOWN_REFERENCE,
                        )
                    }
                }
            }
        }

    fun renderText(content: Content): String =
        renderTextEmail("Ein Print-on-Demand-Auftrag braucht eine Entscheidung") {
            appendLine(content.headline)
            appendLine(content.instruction)
            appendLine()
            appendLine("Produktionsauftrag:   #${content.jobId}")
            appendLine("Bestellnummer:        ORD-${content.orderId}")
            appendLine("SPOD-Auftragsnummer:  ${content.externalReference ?: UNKNOWN_REFERENCE}")
        }

    private fun FlowContent.labelledValue(label: String, value: String) {
        +"$label: "
        strong { +value }
        br
    }

    /**
     * What the mail says, derived from the bounded reason alone: no provider text ever reaches this
     * template, so the copy is a table of three cases rather than a message passed through.
     */
    data class Content(
        val jobId: Long,
        val orderId: Long,
        val reason: QueuedEmail.SpodOpsAlert.Reason,
        val externalReference: String?,
    ) {
        val headline: String
            get() =
                when (reason) {
                    QueuedEmail.SpodOpsAlert.Reason.CANCELLED ->
                        "SPOD hat den Auftrag storniert. Der Auftrag wird nicht produziert."
                    QueuedEmail.SpodOpsAlert.Reason.NEEDS_ACTION ->
                        "SPOD meldet, dass der Auftrag eine Rückmeldung braucht."
                    QueuedEmail.SpodOpsAlert.Reason.OUTCOME_UNKNOWN ->
                        "Zweimal in Folge blieb offen, ob bei SPOD ein Auftrag angelegt wurde. " +
                            "Der Auftrag wurde deshalb angehalten."
                    QueuedEmail.SpodOpsAlert.Reason.SUBMISSION_BLOCKED ->
                        "Der Auftrag kann bei SPOD nicht angelegt werden und kommt von allein " +
                            "nicht weiter."
                }

        val instruction: String
            get() =
                when (reason) {
                    QueuedEmail.SpodOpsAlert.Reason.CANCELLED ->
                        "Bitte im SPOD-Backoffice prüfen und die Bestellung im Shop erstatten " +
                            "oder neu anlegen."
                    QueuedEmail.SpodOpsAlert.Reason.NEEDS_ACTION ->
                        "Bitte im SPOD-Backoffice nachsehen, was dort offen ist."
                    QueuedEmail.SpodOpsAlert.Reason.OUTCOME_UNKNOWN ->
                        "Bitte im SPOD-Backoffice nachsehen, ob ein Auftrag mit dieser " +
                            "Bestellnummer existiert, bevor der Auftrag wieder freigegeben wird."
                    QueuedEmail.SpodOpsAlert.Reason.SUBMISSION_BLOCKED ->
                        "Bitte den Fehlercode des Auftrags in der Logistik prüfen: entweder die " +
                            "Stammdaten korrigieren oder die Bestellung erstatten."
                }
    }

    private const val UNKNOWN_REFERENCE = "noch keine"

    private const val FOOTER =
        "Diese Nachricht kommt vom Voenix Shop und richtet sich an den Betrieb, " +
            "nicht an Kundinnen und Kunden."
}

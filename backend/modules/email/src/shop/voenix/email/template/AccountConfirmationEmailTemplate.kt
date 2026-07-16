package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import shop.voenix.email.template.HtmlEmailLayout.actionSection
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.explanationSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object AccountConfirmationEmailTemplate {
    const val SUBJECT = "Bitte bestätige deine E-Mail-Adresse"

    fun renderHtml(actionUrl: String): String =
        renderHtmlEmail(
            footer =
                "Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren."
        ) {
            contentSection {
                h2 { +"E-Mail-Adresse bestätigen" }
                p {
                    +"Vielen Dank für deine Registrierung bei Voenix Shop! "
                    +"Bitte bestätige deine E-Mail-Adresse, um dein Konto zu aktivieren."
                }
            }
            actionSection(actionUrl, "E-Mail bestätigen")
            explanationSection(
                actionUrl = actionUrl,
                validity =
                    "Dieser Link ist 24 Stunden gültig. Danach musst du eine neue " +
                        "Bestätigungs-E-Mail anfordern.",
            )
        }

    fun renderText(actionUrl: String): String =
        renderTextEmail("E-Mail-Adresse bestätigen") {
            appendLine("Vielen Dank für deine Registrierung bei Voenix Shop!")
            appendLine()
            appendLine("Bitte öffne den folgenden Link, um deine E-Mail-Adresse zu bestätigen:")
            appendLine()
            appendLine(actionUrl)
            appendLine()
            appendLine("Dieser Link ist 24 Stunden gültig.")
            appendLine()
            appendLine(
                "Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren."
            )
        }
}

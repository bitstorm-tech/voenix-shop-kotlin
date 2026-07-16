package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.strong
import shop.voenix.email.template.HtmlEmailLayout.actionSection
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.explanationSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object ChangeEmailConfirmationEmailTemplate {
    const val SUBJECT = "Bitte bestätige deine neue E-Mail-Adresse"

    fun renderHtml(actionUrl: String, newEmail: String): String =
        renderHtmlEmail(
            footer =
                "Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail " +
                    "ignorieren. Deine E-Mail-Adresse bleibt unverändert."
        ) {
            contentSection {
                h2 { +"E-Mail-Adresse ändern" }
                p {
                    +"Du hast eine Änderung deiner E-Mail-Adresse zu "
                    strong { +newEmail }
                    +" angefordert. Bitte bestätige die neue E-Mail-Adresse."
                }
            }
            actionSection(actionUrl, "E-Mail-Adresse bestätigen")
            explanationSection(
                actionUrl = actionUrl,
                validity =
                    "Dieser Link ist 24 Stunden gültig. Danach musst du die Änderung erneut anfordern.",
            )
        }

    fun renderText(actionUrl: String, newEmail: String): String =
        renderTextEmail("E-Mail-Adresse ändern") {
            appendLine("Du hast eine Änderung deiner E-Mail-Adresse zu $newEmail angefordert.")
            appendLine()
            appendLine("Bitte öffne den folgenden Link, um die neue E-Mail-Adresse zu bestätigen:")
            appendLine()
            appendLine(actionUrl)
            appendLine()
            appendLine("Dieser Link ist 24 Stunden gültig.")
            appendLine()
            appendLine(
                "Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail ignorieren."
            )
        }
}

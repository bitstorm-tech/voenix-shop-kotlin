package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import shop.voenix.email.template.HtmlEmailLayout.actionSection
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.explanationSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object PasswordResetEmailTemplate {
    const val SUBJECT = "Setze dein Passwort zurück"

    fun renderHtml(actionUrl: String): String =
        renderHtmlEmail(
            footer =
                "Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail ignorieren."
        ) {
            contentSection {
                h2 { +"Passwort zurücksetzen" }
                p {
                    +"Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen. "
                    +"Bitte vergib über den Link ein neues Passwort."
                }
            }
            actionSection(actionUrl, "Passwort zurücksetzen")
            explanationSection(
                actionUrl = actionUrl,
                validity =
                    "Dieser Link ist zeitlich begrenzt gültig. Danach musst du einen neuen Link " +
                        "anfordern.",
            )
        }

    fun renderText(actionUrl: String): String =
        renderTextEmail("Passwort zurücksetzen") {
            appendLine("Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen.")
            appendLine()
            appendLine("Bitte öffne den folgenden Link, um ein neues Passwort zu vergeben:")
            appendLine()
            appendLine(actionUrl)
            appendLine()
            appendLine("Dieser Link ist zeitlich begrenzt gültig.")
            appendLine()
            appendLine(
                "Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail ignorieren."
            )
        }
}

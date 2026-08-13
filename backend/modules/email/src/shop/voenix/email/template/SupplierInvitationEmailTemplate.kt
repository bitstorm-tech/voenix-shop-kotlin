package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import shop.voenix.email.template.HtmlEmailLayout.actionSection
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.explanationSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

/**
 * The invitation to a supplier login created by an administrator.
 *
 * It links to the same set-password endpoint as the password reset, but it deliberately does not
 * reuse [PasswordResetEmailTemplate]: that copy says "you requested", and nobody requested this
 * mail. The recipient learns that Voenix created the access, not that they asked for it.
 */
internal object SupplierInvitationEmailTemplate {
    const val SUBJECT = "Dein Zugang zum Voenix Lieferantenportal"

    fun renderHtml(actionUrl: String): String =
        renderHtmlEmail(
            footer =
                "Falls du keinen Lieferantenzugang bei Voenix Shop erwartest, kannst du diese E-Mail ignorieren."
        ) {
            contentSection {
                h2 { +"Lieferantenzugang einrichten" }
                p {
                    +"Voenix Shop hat für diese E-Mail-Adresse einen Lieferantenzugang angelegt. "
                    +"Bitte vergib über den Link dein Passwort, um den Zugang zu nutzen."
                }
            }
            actionSection(actionUrl, "Passwort festlegen")
            explanationSection(
                actionUrl = actionUrl,
                validity =
                    "Dieser Link ist 24 Stunden gültig. Danach kannst du über " +
                        "„Passwort vergessen“ einen neuen Link anfordern.",
            )
        }

    fun renderText(actionUrl: String): String =
        renderTextEmail("Lieferantenzugang einrichten") {
            appendLine("Voenix Shop hat für diese E-Mail-Adresse einen Lieferantenzugang angelegt.")
            appendLine()
            appendLine("Bitte öffne den folgenden Link, um dein Passwort festzulegen:")
            appendLine()
            appendLine(actionUrl)
            appendLine()
            appendLine(
                "Dieser Link ist 24 Stunden gültig. Danach kannst du über „Passwort vergessen“ " +
                    "einen neuen Link anfordern."
            )
            appendLine()
            appendLine(
                "Falls du keinen Lieferantenzugang bei Voenix Shop erwartest, kannst du diese E-Mail ignorieren."
            )
        }
}

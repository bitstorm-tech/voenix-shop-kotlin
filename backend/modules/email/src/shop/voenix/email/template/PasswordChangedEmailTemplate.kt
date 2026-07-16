package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.td
import kotlinx.html.tr
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object PasswordChangedEmailTemplate {
    const val SUBJECT = "Dein Passwort wurde geändert"

    fun renderHtml(): String =
        renderHtmlEmail(
            footer =
                "Diese E-Mail wurde automatisch versendet, weil dein Passwort bei Voenix Shop " +
                    "geändert wurde."
        ) {
            contentSection {
                h2 { +"Passwort geändert" }
                p { +"Dein Passwort wurde soeben erfolgreich geändert." }
            }
            tr {
                td {
                    style = "padding:0 32px 24px"
                    p {
                        +"Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns "
                        +"bitte umgehend."
                    }
                }
            }
        }

    fun renderText(): String =
        renderTextEmail("Passwort geändert") {
            appendLine("Dein Passwort wurde soeben erfolgreich geändert.")
            appendLine()
            appendLine(
                "Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns bitte umgehend."
            )
        }
}

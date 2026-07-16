package shop.voenix.email.template

import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.td
import kotlinx.html.tr
import shop.voenix.email.template.HtmlEmailLayout.contentSection
import shop.voenix.email.template.HtmlEmailLayout.render as renderHtmlEmail
import shop.voenix.email.template.TextEmailLayout.render as renderTextEmail

internal object ChangeEmailNotificationEmailTemplate {
    const val SUBJECT = "Änderung deiner E-Mail-Adresse angefordert"

    fun renderHtml(newEmail: String): String =
        renderHtmlEmail(
            footer =
                "Diese E-Mail wurde automatisch versendet, weil eine Änderung deiner " +
                    "E-Mail-Adresse bei Voenix Shop angefordert wurde."
        ) {
            contentSection {
                h2 { +"E-Mail-Änderung angefordert" }
                p {
                    +"Es wurde eine Änderung deiner E-Mail-Adresse zu "
                    strong { +newEmail }
                    +" angefordert. Wenn die neue Adresse bestätigt wird, wird diese "
                    +"E-Mail-Adresse nicht mehr mit deinem Konto verknüpft sein."
                }
            }
            tr {
                td {
                    style = "padding:0 32px 24px"
                    p {
                        +"Falls du diese Änderung nicht selbst angefordert hast, ändere bitte "
                        +"umgehend dein Passwort."
                    }
                }
            }
        }

    fun renderText(newEmail: String): String =
        renderTextEmail("E-Mail-Änderung angefordert") {
            appendLine("Es wurde eine Änderung deiner E-Mail-Adresse zu $newEmail angefordert.")
            appendLine(
                "Wenn die neue Adresse bestätigt wird, wird diese E-Mail-Adresse nicht mehr mit " +
                    "deinem Konto verknüpft sein."
            )
            appendLine()
            appendLine(
                "Falls du diese Änderung nicht selbst angefordert hast, ändere bitte umgehend dein Passwort."
            )
        }
}

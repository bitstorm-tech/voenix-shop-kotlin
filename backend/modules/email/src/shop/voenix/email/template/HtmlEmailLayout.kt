package shop.voenix.email.template

import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.TABLE
import kotlinx.html.TD
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.lang
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.title
import kotlinx.html.tr

internal object HtmlEmailLayout {
    fun render(footer: String, content: TABLE.() -> Unit): String =
        "<!DOCTYPE html>\n" +
            createHTML(prettyPrint = false).html {
                emailDocument(footer = footer, content = content)
            }

    private fun HTML.emailDocument(footer: String, content: TABLE.() -> Unit) {
        lang = "de"
        head {
            meta { charset = "utf-8" }
            meta {
                name = "viewport"
                this.content = "width=device-width, initial-scale=1.0"
            }
            title { +"Voenix Shop" }
        }
        body { emailBody(footer = footer, content = content) }
    }

    private fun BODY.emailBody(footer: String, content: TABLE.() -> Unit) {
        style =
            "margin:0;padding:0;background:#f4f4f5;" +
                "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica," +
                "Arial,sans-serif"
        table {
            attributes["role"] = "presentation"
            attributes["cellpadding"] = "0"
            attributes["cellspacing"] = "0"
            style = "width:100%;background:#f4f4f5"
            tr {
                td {
                    style = "padding:24px 0"
                    emailContainer(footer = footer, content = content)
                }
            }
        }
    }

    private fun TD.emailContainer(footer: String, content: TABLE.() -> Unit) {
        table {
            attributes["role"] = "presentation"
            attributes["cellpadding"] = "0"
            attributes["cellspacing"] = "0"
            style =
                "width:600px;max-width:100%;margin:0 auto;background:#fff;" +
                    "border-radius:8px;overflow:hidden"
            emailHeader()
            content()
            emailFooter(footer)
        }
    }

    private fun TABLE.emailHeader() {
        tr {
            td {
                style = "background:#18181b;color:#fff;padding:24px 32px;text-align:center"
                h1 {
                    style = "margin:0;font-size:24px;font-weight:600"
                    +"Voenix Shop"
                }
            }
        }
    }

    private fun TABLE.emailFooter(footer: String) {
        tr {
            td {
                style = "background:#f4f4f5;padding:24px 32px;text-align:center"
                p {
                    style = "margin:0;color:#71717a;font-size:13px;line-height:1.5"
                    +footer
                }
                p {
                    style = "margin:8px 0 0;color:#a1a1aa;font-size:12px"
                    +"© Voenix Shop"
                }
            }
        }
    }

    fun TABLE.contentSection(content: FlowContent.() -> Unit) {
        tr {
            td {
                style = "padding:32px 32px 16px"
                content()
            }
        }
    }

    fun TABLE.actionSection(actionUrl: String, label: String) {
        tr {
            td {
                style = "padding:0 32px 24px;text-align:center"
                a(href = actionUrl) {
                    style =
                        "display:inline-block;background:#18181b;color:#fff;padding:14px 32px;" +
                            "border-radius:6px;text-decoration:none"
                    +label
                }
            }
        }
    }

    fun TABLE.explanationSection(actionUrl: String, validity: String) {
        tr {
            td {
                style = "padding:0 32px 24px"
                p { +"Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:" }
                p {
                    style = "word-break:break-all"
                    +actionUrl
                }
                p { +validity }
            }
        }
    }
}

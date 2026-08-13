package shop.voenix.order

import shop.voenix.email.EmailActionUrl
import shop.voenix.http.FrontendBaseUrl

/**
 * The frontend links this module mails, built from the application-wide [FrontendBaseUrl].
 *
 * The path lives here, next to the mail that uses it, exactly like the account module keeps its
 * link paths in `AccountMailer`: a frontend route is knowledge of the module that sends somebody
 * there, not of the platform that knows the host.
 *
 * The result is an [EmailActionUrl] rather than a `String`, so the link stays redacted in every
 * `toString` it ever passes through — the queued mail carries it as a field, and a data class
 * prints its fields.
 *
 * Nothing is percent-encoded here and nothing needs to be: an [OrderAccessToken] is URL-safe
 * Base64, which is exactly the alphabet a path segment may contain unescaped.
 */
internal class OrderLinks(private val baseUrl: FrontendBaseUrl) {
    /** The permanent page of one order — the durable handle the confirmation mail hands out. */
    fun orderUrl(token: OrderAccessToken): EmailActionUrl =
        EmailActionUrl("${baseUrl.value}$ORDER_PATH${token.value}")

    private companion object {
        const val ORDER_PATH = "/order/"
    }
}

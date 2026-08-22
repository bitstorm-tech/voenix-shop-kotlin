package shop.voenix.production.fulfillment

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The carriers a supplier may report a shipment with, and the tracking page each of them has.
 *
 * The list is bounded on purpose, and the same list the database CHECK of `production_jobs` holds
 * (decision J2 of issue #119). The reason is the mail: the shipping notification leaves under the
 * shop's name, so the tracking link in it is built **here**, from a template of this enum and the
 * number the supplier typed. A caller can never supply a URL — that would turn the shop's mail into
 * a phishing carrier for whoever gets a supplier login.
 *
 * [OTHER] is the honest end of the list: the shipment has a number, the shop has no page to point
 * at, and the mail shows the number as plain text instead of inventing a link.
 */
internal enum class ShippingCarrier(
    /** How the carrier is named to a customer; `null` where the shop has no name to print. */
    val displayName: String?,
    private val trackingUrlTemplate: String?,
) {
    DHL(
        displayName = "DHL",
        trackingUrlTemplate =
            "https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode=",
    ),
    DPD(
        displayName = "DPD",
        trackingUrlTemplate = "https://tracking.dpd.de/status/de_DE/parcel/",
    ),
    GLS(
        displayName = "GLS",
        trackingUrlTemplate = "https://gls-group.com/DE/de/paketverfolgung?match=",
    ),
    HERMES(
        displayName = "Hermes",
        trackingUrlTemplate =
            "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#",
    ),
    UPS(
        displayName = "UPS",
        trackingUrlTemplate = "https://www.ups.com/track?loc=de_DE&tracknum=",
    ),
    DEUTSCHE_POST(
        displayName = "Deutsche Post",
        trackingUrlTemplate = "https://www.deutschepost.de/de/s/sendungsverfolgung.html?piececode=",
    ),
    OTHER(displayName = null, trackingUrlTemplate = null);

    /**
     * The carrier's tracking page for [trackingNumber], or `null` when there is none to build — no
     * template ([OTHER]) or no number at all.
     *
     * The number is percent-encoded before it reaches the URL, so a number with a character that
     * means something in a query string cannot change the link it lands in.
     */
    fun trackingUrl(trackingNumber: String?): String? {
        val number = trackingNumber?.takeIf(String::isNotBlank) ?: return null
        val template = trackingUrlTemplate ?: return null
        // `URLEncoder` writes HTML form encoding, where a space becomes `+`. That is only correct
        // in a query string; the templates above also end in a path segment (DPD) and a fragment
        // (Hermes), where `+` stays a literal plus and the carrier looks up the wrong number.
        // `%20` is the space in all three places, so the replace makes one encoding fit them all.
        return template + URLEncoder.encode(number, StandardCharsets.UTF_8).replace("+", "%20")
    }

    companion object {
        /** The carrier of a stored or submitted name, or `null` if it is not one of ours. */
        fun of(name: String?): ShippingCarrier? = entries.firstOrNull { it.name == name }

        /**
         * The carrier a fulfillment channel reported, mapped onto this list — [OTHER] when it is a
         * name the shop has no page for.
         *
         * The comparison is case- and separator-insensitive, because a partner writes "DHL",
         * "Deutsche Post", and "deutsche_post" for the same three carriers and none of those
         * spellings is a promise. What is *not* insensitive is the result: an unrecognized name
         * never becomes a guess. It becomes [OTHER], the shop's mail prints the tracking number as
         * plain text, and the raw name is kept next to it for an operator to read.
         */
        fun ofReportedName(name: String?): ShippingCarrier {
            val normalized =
                name
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    ?.replace(SEPARATORS, "_")
                    ?.trim('_')
                    ?.takeIf(String::isNotEmpty) ?: return OTHER
            return entries.firstOrNull { entry -> entry != OTHER && entry.name == normalized }
                ?: OTHER
        }

        /** Everything that is not a letter or a digit separates words in a reported name. */
        private val SEPARATORS = Regex("[^A-Z0-9]+")
    }
}

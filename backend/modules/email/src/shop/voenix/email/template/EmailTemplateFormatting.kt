package shop.voenix.email.template

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How dates and money look in a German mail. This lives beside the templates because it is
 * presentation, exactly like the wording around it: `1234` cents only becomes `"12,34 €"` because a
 * German reader is going to read it, and that decision belongs where the German sentences are.
 *
 * The renderer keeps the arithmetic (what a line costs) and asks this object only for the strings
 * the templates print, so a template `Content` stays a bag of ready-to-print `String`s.
 */
internal object EmailTemplateFormatting {
    /** The German short date, for example `14.08.2026`. */
    fun date(date: LocalDate): String = DATE_FORMAT.format(date)

    /** A money amount given in cents, for example `"1.234,50 €"`. */
    fun price(cents: Long): String {
        val euros = BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY)
        return PRICE_FORMAT.get().format(euros) + " €"
    }

    /** Free shipping is named, not printed as `0,00 €`, because that is the friendlier promise. */
    fun shippingCost(cents: Long): String = if (cents == 0L) "Kostenlos" else price(cents)

    /**
     * A discount as the customer expects to see it — with a leading minus. `null` means "there is
     * no discount line at all": the templates print the line only when this returns a value, so a
     * zero discount stays invisible instead of showing `-0,00 €`.
     */
    fun discount(cents: Long): String? = if (cents > 0L) "-" + price(cents) else null

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * `DecimalFormat` is not thread-safe and the worker renders on several threads, so every thread
     * formats with its own instance instead of sharing one behind a lock.
     */
    private val PRICE_FORMAT: ThreadLocal<DecimalFormat> = ThreadLocal.withInitial {
        DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY))
    }
}

package shop.voenix.payment

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.order.OrderPaymentStatus
import shop.voenix.order.PayableOrder

/**
 * The one place in this backend that knows Mollie's HTTP API: three requests go out, and everything
 * that can come back other than a usable answer becomes `null` or `false`.
 *
 * Three rules shape every line of it.
 *
 * **Nothing the provider wrote is ever logged.** Not an error body, not a decoder message (which
 * quotes the input it stumbled over), and not an unrecognized status word. What is logged is this
 * adapter's own context plus, at most, the HTTP status *number*. That rule is why this module talks
 * to Mollie by hand: an SDK would log wherever it pleases.
 *
 * **Money is formatted, never rendered.** The amount goes out as an exact two-decimal string built
 * from the integer cents, so no default locale can turn 4070 cents into `40,70` and no floating
 * point can turn it into `40.699999`.
 *
 * **The customer's data is normalized to what Mollie accepts, or omitted.** A phone number that
 * cannot be turned into E.164 is left out of the request rather than sent as typed, because Mollie
 * rejects the whole payment over one malformed field — and a rejected payment is a lost order.
 */
internal class MolliePaymentClient
private constructor(
    private val settings: MollieSettings,
    private val client: HttpClient,
) : MolliePayments, AutoCloseable {
    /**
     * The adapter a deployment runs: it builds its own client on the CIO engine, and because that
     * engine came from a factory rather than from a caller, Ktor owns it — [close] closes both.
     */
    constructor(
        settings: MollieSettings
    ) : this(settings, HttpClient(CIO) { configureMollieClient() })

    /**
     * The same adapter on an [engine] somebody else supplied — a test's `MockEngine`. Passing an
     * engine *instance* leaves Ktor's `manageEngine` off, so [close] closes this client but not the
     * engine: whoever created the engine keeps owning it.
     *
     * The configuration is the deployment's own, so a request made through this adapter carries the
     * very timeouts, the redirect rule and the `expectSuccess` setting that a deployment sends.
     */
    constructor(
        settings: MollieSettings,
        engine: HttpClientEngine,
    ) : this(settings, HttpClient(engine) { configureMollieClient() })

    override suspend fun create(
        order: PayableOrder,
        idempotencyKey: String,
    ): MolliePayment? =
        upstream("The Mollie payment could not be created") {
            val response =
                client.post(settings.apiUrl) {
                    header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                    header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    contentType(ContentType.Application.Json)
                    // Serialized here rather than by a content-negotiation plugin: the
                    // request shape — an omitted phone above all — is this adapter's
                    // promise, and it must not depend on how a client was configured.
                    setBody(JSON.encodeToString(createRequest(order)))
                }
            val what = "creating the payment of order ${order.orderId}"
            val created = response.readPayment(what) ?: return@upstream null
            // Blank, not only absent: a link the customer cannot be sent to is a creation that did
            // not produce a checkout, whichever of the two shapes Mollie answers it in.
            if (created.checkoutUrl.isNullOrBlank()) {
                logger.error("Mollie answered {} without a checkout URL", what)
                return@upstream null
            }
            created
        }

    override suspend fun find(molliePaymentId: String): MolliePayment? =
        upstream("The Mollie payment could not be read") {
            val found =
                client
                    .get("${settings.apiUrl}/$molliePaymentId") {
                        header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                    }
                    .readPayment("reading payment $molliePaymentId") ?: return@upstream null
            // An answer about a different payment is unusable whatever it says: everything the
            // service does with it — the amount check, the status write — is keyed to the payment
            // that was asked about. The id the answer carried stays out of the log; it is provider
            // output.
            if (found.id != molliePaymentId) {
                logger.error(
                    "Mollie answered the read of payment {} with a different payment",
                    molliePaymentId,
                )
                return@upstream null
            }
            found
        }

    /**
     * Cancels a payment nobody will be sent to. Mollie answers `422` for a payment it will not
     * cancel — one that is already paid or already gone — which is a fact about the payment and not
     * a failure of this call, so it is logged like every other refusal and answered with `false`.
     */
    override suspend fun cancel(molliePaymentId: String): Boolean =
        upstream("The Mollie payment could not be cancelled") {
            val response =
                client.delete("${settings.apiUrl}/$molliePaymentId") {
                    header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                }
            response.bodyAsChannel().cancel()
            if (!response.status.isSuccess()) {
                logger.warn(
                    "Mollie refused to cancel payment {} with status {}",
                    molliePaymentId,
                    response.status.value,
                )
                return@upstream false
            }
            true
        } ?: false

    override fun close() {
        client.close()
    }

    /**
     * The payment in [this] answer, or `null` when Mollie refused the call or said something this
     * adapter cannot act on.
     *
     * [what] names the step *in this adapter's own words* — the order a payment is created for, the
     * id a read asked about — and it is the only context any of these log lines carries. Nothing
     * from the answer itself does, which is why a truncated answer is a decoding failure one level
     * up rather than a line naming the id Mollie sent.
     */
    private suspend fun HttpResponse.readPayment(what: String): MolliePayment? {
        if (!status.isSuccess()) {
            logger.error("Mollie refused {} with status {}", what, status.value)
            bodyAsChannel().cancel()
            return null
        }
        val answer = JSON.decodeFromString<MolliePaymentResponse>(bodyAsText())
        val reportedStatus = statusOfProviderValue(answer.status)
        val amountCents = answer.amount.cents()
        if (reportedStatus == null) {
            // The unknown word itself is provider output and stays out of the log; the webhook
            // answers 502 so Mollie retries once this backend knows it.
            logger.error(
                "Mollie reported a payment status this backend does not know while {}",
                what,
            )
        }
        if (amountCents == null) {
            logger.error("Mollie reported an unusable amount while {}", what)
        }
        return if (reportedStatus == null || amountCents == null) {
            null
        } else {
            MolliePayment(
                id = answer.id,
                status = reportedStatus,
                amountCents = amountCents,
                checkoutUrl = answer.links?.checkout?.href,
            )
        }
    }

    /**
     * Runs one provider call and reports every way it can fail as the absent answer the
     * [MolliePayments] contract asks for, with [what] naming the call in the log.
     *
     * A decoding failure is logged by its exception class and never by its message: the message of
     * a `kotlinx.serialization` decoding error quotes the input it stumbled over, which is provider
     * output. A transport failure carries no provider body and is logged in full.
     */
    private suspend fun <T : Any> upstream(
        what: String,
        step: suspend () -> T?,
    ): T? =
        try {
            step()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SerializationException) {
            logger.error(
                "$what: the provider answer could not be read ({})",
                exception::class.simpleName,
            )
            null
        } catch (exception: IOException) {
            logger.error("$what: the provider could not be reached", exception)
            null
        }

    /** The one request shape this shop sends Mollie. */
    private fun createRequest(order: PayableOrder): CreatePaymentBody =
        CreatePaymentBody(
            amount = MollieAmount(currency = CURRENCY, value = order.totalCents.toAmount()),
            description = "Order #${order.orderId}",
            redirectUrl = redirectUrl(order.orderId),
            webhookUrl = settings.webhookUrl,
            billingAddress = address(order, order.billingAddress),
            shippingAddress = address(order, order.shippingAddress),
            metadata = PaymentMetadata(orderId = order.orderId),
        )

    private fun address(
        order: PayableOrder,
        address: PayableOrder.Address,
    ): MollieAddress =
        MollieAddress(
            givenName = address.firstName,
            familyName = address.lastName,
            email = order.email,
            phone = normalizedPhone(order.phone, address.country),
            streetAndNumber = streetAndNumber(address),
            city = address.city,
            postalCode = address.postalCode,
            country = address.country,
        )

    /**
     * Where the customer lands after paying, with the order they paid for appended (deviation D19).
     *
     * Building it with [URLBuilder] rather than by string concatenation is what makes a configured
     * redirect URL that already carries a query work: the parameter is appended to the query the
     * URL has, whatever that is.
     */
    private fun redirectUrl(orderId: Long): String =
        URLBuilder(settings.redirectUrl)
            .apply { parameters.append("orderId", orderId.toString()) }
            .buildString()

    private companion object {
        /**
         * Unknown fields are ignored, and absent ones are simply not sent: Mollie's answers carry
         * far more than the four facts this module uses, and a `null` phone must be omitted rather
         * than transmitted as `null`, which Mollie rejects.
         */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** The whole system is EUR cents (deviation D4); there is no second currency to select. */
        const val CURRENCY = "EUR"
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        val logger: Logger = LoggerFactory.getLogger(MolliePaymentClient::class.java)
    }

    /** What Mollie is asked for. The names are the provider's. */
    @Serializable
    private data class CreatePaymentBody(
        val amount: MollieAmount,
        val description: String,
        val redirectUrl: String,
        val webhookUrl: String,
        val billingAddress: MollieAddress,
        val shippingAddress: MollieAddress,
        val metadata: PaymentMetadata,
    )

    @Serializable private data class PaymentMetadata(val orderId: Long)

    @Serializable
    private data class MollieAmount(
        val currency: String,
        val value: String,
    ) {
        /**
         * This amount back as integer cents, or `null` when it is not a whole number of EUR cents.
         *
         * The currency is checked before the number is even looked at. This whole system is EUR
         * cents (deviation D4), so `amount_cents` of an answer in another currency would be
         * compared against a number that means something else — and the amount check on `PAID` is
         * the one guard against paying too little. An amount this module cannot interpret is not an
         * amount.
         *
         * `intValueExact` is deliberate for the same reason: an amount with a third decimal, or one
         * larger than this shop's columns can hold, is an answer this module refuses to act on
         * rather than one it rounds into something plausible. Neither the unusable value nor the
         * unexpected currency ever reaches a log line.
         */
        fun cents(): Int? =
            if (currency.trim() != CURRENCY) {
                null
            } else {
                runCatching { BigDecimal(value.trim()).movePointRight(2).intValueExact() }
                    .getOrNull()
            }
    }

    @Serializable
    private data class MollieAddress(
        val givenName: String,
        val familyName: String,
        val email: String,
        val phone: String?,
        val streetAndNumber: String,
        val city: String,
        val postalCode: String,
        val country: String,
    )

    /**
     * What Mollie answers, in the fields this module reads.
     *
     * The first three are required on purpose, and the absent defaults they used to carry were a
     * quiet hazard: a truncated answer would have decoded into a payment with an empty id and an
     * amount of zero cents, and the webhook would have reported an amount mismatch nobody could
     * ever settle. Without them the decoder raises, the call answers `null`, and the webhook
     * answers `502` — which Mollie repairs by redelivering.
     *
     * `_links` is genuinely absent on a payment that can no longer be paid, which is why both it
     * and the checkout link inside it stay optional.
     */
    @Serializable
    private data class MolliePaymentResponse(
        val id: String,
        val status: String,
        val amount: MollieAmount,
        @SerialName("_links") val links: Links? = null,
    ) {
        @Serializable
        data class Links(val checkout: Link? = null) {
            @Serializable data class Link(val href: String? = null)
        }
    }
}

/**
 * Everything about the client that is a decision rather than an engine.
 *
 * It is a function of its own for one reason: both constructors of [MolliePaymentClient] apply it,
 * so the client a test drives and the client a deployment runs are configured by the same lines. A
 * test does not rebuild this configuration — it receives it, and reads the timeouts back off a
 * request the adapter itself made. A client whose timeouts silently disappeared would look exactly
 * like this one until the day Mollie stops answering.
 *
 * Redirects are not followed: Mollie's API never answers with one, so a redirect is a refusal to be
 * judged, not a route to be walked. Walking it would replay the request — body, idempotency key,
 * and (for a redirect within the same authority; Ktor drops the header when the authority changes)
 * the bearer credential — against a URL this adapter never chose. `expectSuccess` stays off, so a
 * refusal is a status this adapter judges rather than an exception it catches. The timeouts are
 * short because this is an ordinary API call, not an image generation: a Mollie request that has
 * not answered in ten seconds has failed, and holding a checkout request open longer helps nobody.
 */
private fun HttpClientConfig<*>.configureMollieClient() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 5_000L
private const val REQUEST_TIMEOUT_MILLIS = 10_000L

/**
 * Integer cents as the exact two-decimal string Mollie's API requires.
 *
 * [BigDecimal.valueOf] with a scale of two is the whole conversion: no division, no rounding, and
 * no `String.format`, whose grouping and decimal separator follow the JVM's default locale. On a
 * machine set to German, 4070 cents formatted that way would go out as `40,70` and Mollie would
 * refuse the payment.
 */
private fun Int.toAmount(): String = BigDecimal.valueOf(toLong(), 2).toPlainString()

/**
 * Mollie wants one address line; the shop stores two fields. An empty house number adds nothing.
 */
private fun streetAndNumber(address: PayableOrder.Address): String =
    if (address.houseNumber.isBlank()) {
        address.street
    } else {
        "${address.street} ${address.houseNumber}"
    }

/**
 * The customer's phone number in E.164, or `null` when it cannot be turned into one.
 *
 * The four cases are the legacy matrix, kept exactly: no number at all is absent; a number starting
 * with `+` carries its own country and is parsed without a region hint; a number without `+` is
 * parsed in the country of the address it belongs to; and a number with neither a `+` nor a country
 * to fall back on is absent. Anything that survives parsing but is not a valid number is absent as
 * well.
 *
 * The one correction to the legacy code is deviation D18: the *trimmed* value is what gets parsed,
 * not just what gets inspected for the leading `+`. Leading whitespace used to decide one thing and
 * be parsed as another.
 *
 * Nothing here is ever logged. A phone number is customer data, and a parse failure says everything
 * it needs to by leaving the field out.
 */
private fun normalizedPhone(
    phone: String?,
    country: String,
): String? {
    val trimmed = phone?.trim().orEmpty()
    val trimmedCountry = country.trim()
    val region =
        when {
            trimmed.isBlank() -> null
            trimmed.startsWith("+") -> UNKNOWN_REGION
            trimmedCountry.isNotBlank() -> trimmedCountry.uppercase()
            else -> null
        } ?: return null

    return try {
        val parsed = phoneNumbersInstance.parse(trimmed, region)
        if (phoneNumbersInstance.isValidNumber(parsed)) {
            phoneNumbersInstance.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } else {
            null
        }
    } catch (_: NumberParseException) {
        null
    }
}

private const val UNKNOWN_REGION = "ZZ"

private val phoneNumbersInstance: PhoneNumberUtil = PhoneNumberUtil.getInstance()

/**
 * The status Mollie named, or `null` when it is a word this backend does not know.
 *
 * The unparsed value is deliberately *not* part of the answer and never travels into a log line: it
 * is provider output, and the webhook answers `502` so Mollie retries rather than this backend
 * writing a status nothing downstream could interpret.
 */
private fun statusOfProviderValue(value: String): OrderPaymentStatus? =
    OrderPaymentStatus.entries.firstOrNull { status ->
        status.name.equals(value.trim(), ignoreCase = true)
    }

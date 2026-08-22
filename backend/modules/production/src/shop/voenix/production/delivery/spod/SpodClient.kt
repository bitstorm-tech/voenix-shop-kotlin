package shop.voenix.production.delivery.spod

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.delivery.ProductionDeliveryDestination

/**
 * The one place in this backend that knows the print-on-demand partner's HTTP API. Five calls go
 * out — upload a design, create an order, read an order, confirm an order, ask which placements a
 * product type offers for a design — and everything that can come back other than a usable answer
 * becomes a bounded [SpodError].
 *
 * Four rules shape every line of it.
 *
 * **Nothing the provider wrote is ever logged, and neither is anything of ours that is secret.**
 * Not an error body, not a decoder message (which quotes the input it stumbled over), not the
 * access token, and not the request URL. What is logged is this adapter's own context plus, at
 * most, the HTTP status *number*.
 *
 * **This client never retries.** Retries belong to the database-backed worker, as everywhere in
 * production. That is not a style preference here: the partner offers no idempotency mechanism for
 * `POST /orders`, so a blind retry inside the adapter could produce a second real order whose id
 * nobody knows. [SpodResult.Failed.ambiguous] is how the adapter tells the worker which failures
 * left the outcome undecided.
 *
 * **Requests are paced, not throttled after the fact.** The partner allows 60 requests per minute.
 * A pacer inside the client keeps at least [MIN_REQUEST_INTERVAL_MILLIS] between any two requests
 * it makes, measured on a monotonic clock, so the limit is respected by construction; a `429` that
 * still happens is the retryable code [SpodError.RATE_LIMITED] and the worker's problem.
 *
 * **Where to talk and how to authenticate is a property of the call, not of the client.** Each
 * supplier has its own destination row with its own environment, its own token, and its own
 * timeout, so every call takes a [ProductionDeliveryDestination.Spod] and reads all three off it.
 * One client therefore serves every supplier — and paces all of them together, which is what a
 * per-account rate limit deserves the day a shop grows a second account.
 */
internal class SpodClient
private constructor(
    private val client: HttpClient,
    private val nowMillis: () -> Long,
    private val pause: suspend (Long) -> Unit,
) : AutoCloseable {
    /**
     * The adapter a deployment runs: it builds its own client on the CIO engine, and because that
     * engine came from a factory rather than from a caller, Ktor owns it — [close] closes both.
     */
    constructor() : this(HttpClient(CIO) { configureSpodClient() }, MONOTONIC_MILLIS, ::delay)

    /**
     * The same adapter on an [engine] somebody else supplied — a test's `MockEngine` — with the
     * clock and the sleeping function of the pacer as seams. Passing an engine *instance* leaves
     * Ktor's `manageEngine` off, so [close] closes this client but not the engine: whoever created
     * the engine keeps owning it.
     *
     * The configuration is the deployment's own, so a request made through this adapter carries the
     * very redirect rule and `expectSuccess` setting that a deployment sends.
     */
    constructor(
        engine: HttpClientEngine,
        nowMillis: () -> Long = MONOTONIC_MILLIS,
        pause: suspend (Long) -> Unit = ::delay,
    ) : this(HttpClient(engine) { configureSpodClient() }, nowMillis, pause)

    private val pacer = Mutex()
    private var earliestNextRequestMillis: Long? = null

    /**
     * Uploads one print image as PNG and answers the design id the partner filed it under.
     *
     * A design upload is safe to repeat: an orphaned design costs nothing and produces nothing, so
     * every failure of this call is simply retried by the worker on a later scan.
     */
    suspend fun uploadDesign(
        destination: ProductionDeliveryDestination.Spod,
        fileName: String,
        png: ByteArray,
    ): SpodResult<String> =
        call(destination, "uploading a design") {
            client
                .post(destination.url("/designs/upload")) {
                    spodRequest(destination)
                    setBody(multipartPng(fileName, png))
                }
                .answered { body -> JSON.decodeFromString<SpodDesignResponse>(body).designId }
        }

    /**
     * Creates the order in state `NEW`. It produces nothing and charges nothing until
     * [confirmOrder] is called, which is exactly what makes the worker's one automatic re-create on
     * an ambiguous outcome affordable.
     *
     * @return the partner's order id — the only handle by which this order can ever be read again.
     */
    suspend fun createOrder(
        destination: ProductionDeliveryDestination.Spod,
        request: SpodOrderRequest,
    ): SpodResult<String> =
        call(destination, "creating an order") {
            client
                .post(destination.url("/orders")) {
                    spodRequest(destination)
                    contentType(ContentType.Application.Json)
                    // Serialized here rather than by a content-negotiation plugin: the request
                    // shape is this adapter's promise and must not depend on how a client was
                    // configured.
                    setBody(JSON.encodeToString(request))
                }
                .answered { body -> JSON.decodeFromString<SpodOrderResponse>(body).id }
        }

    /**
     * Reads the current state of an order — `NEW` while it is still inert, `CONFIRMED` once the
     * confirm call went through. The confirmation step reads it first, so a repeated attempt after
     * a crash confirms only what is still unconfirmed.
     */
    suspend fun getOrder(
        destination: ProductionDeliveryDestination.Spod,
        orderId: String,
    ): SpodResult<String> =
        call(destination, "reading an order") {
            client
                .get(destination.url("/orders/$orderId")) { spodRequest(destination) }
                .answered { body -> JSON.decodeFromString<SpodOrderResponse>(body).state }
        }

    /** Turns the inert `NEW` order into a real one. This is the moment production is ordered. */
    suspend fun confirmOrder(
        destination: ProductionDeliveryDestination.Spod,
        orderId: String,
    ): SpodResult<Unit> =
        call(destination, "confirming an order") {
            client
                .post(destination.url("/orders/$orderId/confirm")) { spodRequest(destination) }
                .answered { Unit }
        }

    /**
     * The placements this product type offers for this design. Asked only when the default
     * placement was refused, and answered with the plain hotspot names the partner uses.
     */
    suspend fun availableHotspots(
        destination: ProductionDeliveryDestination.Spod,
        productTypeId: Long,
        designId: String,
    ): SpodResult<List<String>> =
        call(destination, "reading available hotspots") {
            val path = "/productTypes/$productTypeId/hotspots/design/$designId"
            client
                .get(destination.url(path)) { spodRequest(destination) }
                .answered { body ->
                    JSON.decodeFromString<SpodHotspotsResponse>(body).hotspots.map { hotspot ->
                        hotspot.name
                    }
                }
        }

    override fun close() {
        client.close()
    }

    /** Holds no secret of its own — the destinations do, and their own `toString` redacts it. */
    override fun toString(): String = "SpodClient(pacedEveryMillis=$MIN_REQUEST_INTERVAL_MILLIS)"

    /**
     * Runs one paced provider call and turns every way it can fail into a bounded [SpodError], with
     * [what] naming the call in this adapter's own words.
     *
     * The three failures decided here are the ones that never produced a status:
     *
     * - A decoding failure is logged by its exception class and never by its message, because a
     *   `kotlinx.serialization` message quotes the input it failed on, which is provider output.
     *   The answer may well have described an order that exists, so the outcome is ambiguous.
     * - A request timeout means the request went out and the answer never came.
     * - Any other I/O failure is treated as ambiguous too. A reset connection is indistinguishable
     *   from a lost answer, and guessing "it never arrived" is the guess that duplicates an order.
     */
    private suspend fun <T : Any> call(
        destination: ProductionDeliveryDestination.Spod,
        what: String,
        step: suspend () -> SpodResult<T>,
    ): SpodResult<T> =
        try {
            pace()
            step()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SerializationException) {
            logger.error(
                "SPOD destination {}: {} produced an answer that could not be read ({})",
                destination.id,
                what,
                exception::class.simpleName,
            )
            SpodResult.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE, ambiguous = true)
        } catch (exception: HttpRequestTimeoutException) {
            logger.error(
                "SPOD destination {}: {} did not answer within the configured timeout",
                destination.id,
                what,
                exception,
            )
            SpodResult.Failed(SpodError.PROVIDER_UNAVAILABLE, ambiguous = true)
        } catch (exception: IOException) {
            logger.error("SPOD destination {}: {} failed", destination.id, what, exception)
            SpodResult.Failed(SpodError.PROVIDER_UNAVAILABLE, ambiguous = true)
        }

    /**
     * Blocks until at least [MIN_REQUEST_INTERVAL_MILLIS] have passed since the previous request
     * this client made, and books the current one.
     *
     * The lock is held across the wait on purpose: the point of a pacer is that concurrent callers
     * queue up behind each other instead of all sleeping the same interval and then firing
     * together. The clock is monotonic, so a wall-clock adjustment can neither make the pacer wait
     * for hours nor make it stop pacing at all.
     */
    private suspend fun pace() {
        pacer.withLock {
            val now = nowMillis()
            val waitMillis = earliestNextRequestMillis?.minus(now)?.coerceAtLeast(0) ?: 0
            if (waitMillis > 0) pause(waitMillis)
            earliestNextRequestMillis = now + waitMillis + MIN_REQUEST_INTERVAL_MILLIS
        }
    }

    /**
     * One answer judged: a refusal status becomes a bounded failure, a success is handed to [read],
     * and a `null` from [read] is an answer that arrived without the field this adapter needs.
     *
     * The two refusal families are kept apart because the order-creation protocol depends on the
     * difference. A `4xx` is the partner saying "I did not do this" — the outcome is *known*, and
     * re-creating later is safe. A `5xx` is the partner saying nothing usable at all after having
     * received the request, so the order may exist and the outcome is ambiguous. The body of either
     * is drained unread; whatever it said stays with the partner.
     */
    private suspend fun <T : Any> HttpResponse.answered(read: (String) -> T?): SpodResult<T> {
        if (!status.isSuccess()) {
            bodyAsChannel().cancel()
            logger.warn("SPOD refused a request with status {}", status.value)
            val serverSide = status.value >= HttpStatusCode.InternalServerError.value
            val error =
                when {
                    status == HttpStatusCode.TooManyRequests -> SpodError.RATE_LIMITED
                    serverSide -> SpodError.PROVIDER_UNAVAILABLE
                    else -> SpodError.REFUSED
                }
            return SpodResult.Failed(error, ambiguous = serverSide)
        }
        val value = read(bodyAsText())
        return if (value == null) {
            SpodResult.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE, ambiguous = true)
        } else {
            SpodResult.Answered(value)
        }
    }

    private fun HttpRequestBuilder.spodRequest(destination: ProductionDeliveryDestination.Spod) {
        header(ACCESS_TOKEN_HEADER, destination.accessToken)
        timeout {
            val millis = destination.timeoutSeconds.toLong() * MILLIS_PER_SECOND
            connectTimeoutMillis = millis
            requestTimeoutMillis = millis
            socketTimeoutMillis = millis
        }
    }

    private fun multipartPng(fileName: String, png: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    key = "file",
                    value = png,
                    headers =
                        headersOf(
                            HttpHeaders.ContentDisposition to listOf("filename=\"$fileName\""),
                            HttpHeaders.ContentType to listOf(ContentType.Image.PNG.toString()),
                        ),
                )
            }
        )

    internal companion object {
        /**
         * The partner allows 60 requests per minute. One second would be exactly the limit and
         * therefore no margin at all; the extra 50 ms absorbs the scheduling jitter between reading
         * the clock and the request actually leaving.
         */
        const val MIN_REQUEST_INTERVAL_MILLIS: Long = 1_050

        const val ACCESS_TOKEN_HEADER: String = "X-SPOD-ACCESS-TOKEN"

        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Monotonic and immune to wall-clock adjustments — the only clock a pacer may trust. */
        private val MONOTONIC_MILLIS: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }

        /**
         * Unknown fields are ignored: the partner's answers carry far more than what is read.
         *
         * `isLenient` is the same setting the webhook's JSON uses, for the same reason: the
         * partner's ids arrive as numbers in some fields and as strings in others, and an order id
         * answered as `{"id": 12345}` must read into the same `String` a quoted one does. Without
         * it, a creation this shop *did* perform would fail to decode, count as ambiguous, and
         * quarantine the job after a second orphan — on every single order.
         */
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
        }

        private val logger: Logger = LoggerFactory.getLogger(SpodClient::class.java)
    }
}

/** Where a call goes, derived from the destination's environment and never from a column. */
private fun ProductionDeliveryDestination.Spod.url(path: String): String =
    environment.baseUrl + path

/**
 * The typed outcome of one provider call.
 *
 * [Failed] carries only a bounded [SpodError] — a provider body, a token, or a URL can never reach
 * a persisted error column by construction — plus [Failed.ambiguous], the one fact the worker's
 * idempotency protocol depends on.
 */
internal sealed interface SpodResult<out T> {
    data class Answered<T>(val value: T) : SpodResult<T>

    /**
     * @param ambiguous whether the call may have taken effect without saying so. `false` only for a
     *   refusal the partner stated explicitly; a timeout, a reset, an unreadable answer, and any
     *   `5xx` are all `true`, because the safe assumption for an order creation is "it may exist".
     */
    data class Failed(val error: SpodError, val ambiguous: Boolean) : SpodResult<Nothing>
}

/** The bounded vocabulary of provider-call failures; the names are the persisted error codes. */
internal enum class SpodError {
    /** The partner's 60-per-minute limit answered `429`; the worker simply tries again later. */
    RATE_LIMITED,

    /** Unreachable, timed out, or answered `5xx` — nothing usable came back. */
    PROVIDER_UNAVAILABLE,

    /** An answer arrived but could not be read as what this adapter expects. */
    PROVIDER_ANSWER_UNREADABLE,

    /** The partner refused the call with a `4xx`. What it said about it stays with it. */
    REFUSED,
}

/**
 * The one order shape this shop sends the partner. The property names are the provider's, so no
 * per-property rename annotation is needed anywhere in this file.
 *
 * [orderItems] is always empty and still always sent: every line of this shop is a unique print
 * image, so nothing is ever ordered from a pre-created article — but the field is required, and an
 * omitted required field is a rejected order.
 *
 * [state] is always `NEW`. The partner also accepts `CONFIRMED` at creation, and this shop
 * deliberately never uses it: an order created `NEW` produces nothing until it is confirmed, which
 * is the only reason a creation whose outcome nobody knows can be repeated at all.
 */
@Serializable
internal data class SpodOrderRequest(
    val externalOrderReference: String,
    val email: String,
    val phone: String,
    val shipping: SpodShipping,
    val oneTimeItems: List<SpodOneTimeItem>,
    val orderItems: List<String> = emptyList(),
    val state: String = "NEW",
)

@Serializable
internal data class SpodShipping(
    val address: SpodAddress,
    /**
     * Standard shipping, preset at creation; the customer never chooses (issue #205, decision 5).
     */
    val preferredType: String = "STANDARD",
)

@Serializable
internal data class SpodAddress(
    val firstName: String,
    val lastName: String,
    val street: String,
    val city: String,
    val country: String,
    val zipCode: String,
)

/**
 * One printable product of the order: which product type it is, which design goes on it and where,
 * and how many of which size and colour are wanted.
 *
 * The grouping is the partner's, not ours: one entry per (product type, design) pair, with a
 * [quantityItems] line per size/appearance combination. Two shirt lines of the same order that
 * differ only in size therefore travel as one entry with two quantity lines.
 */
@Serializable
internal data class SpodOneTimeItem(
    val productTypeId: Long,
    val quantityItems: List<SpodQuantityItem>,
    val configurations: List<SpodConfiguration>,
)

@Serializable
internal data class SpodQuantityItem(
    val quantity: Int,
    val sizeId: Long,
    val appearanceId: Long,
)

/** Where the design sits: the view of the garment and one of that view's named hotspots. */
@Serializable
internal data class SpodConfiguration(
    val image: SpodConfigurationImage,
    val view: String,
    val hotspot: String,
)

@Serializable internal data class SpodConfigurationImage(val designId: String)

/** The design upload's answer, in the one field this module reads. */
@Serializable private data class SpodDesignResponse(val designId: String)

/** An order's answer, in the two fields this module reads. */
@Serializable private data class SpodOrderResponse(val id: String, val state: String? = null)

@Serializable private data class SpodHotspotsResponse(val hotspots: List<SpodHotspot> = emptyList())

@Serializable private data class SpodHotspot(val name: String)

/**
 * Everything about the client that is a decision rather than an engine.
 *
 * It is a function of its own for one reason: both constructors of [SpodClient] apply it, so the
 * client a test drives and the client a deployment runs are configured by the same lines.
 *
 * Redirects are not followed: this API never answers with one, so a redirect is a refusal to be
 * judged, not a route to be walked. Walking it would replay the request — the order body and the
 * access token — against a URL this adapter never chose. `expectSuccess` stays off, so a refusal is
 * a status this adapter judges rather than an exception it catches. The timeout plugin is installed
 * without values, because every call sets the destination's own timeout per request.
 */
private fun HttpClientConfig<*>.configureSpodClient() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout)
}

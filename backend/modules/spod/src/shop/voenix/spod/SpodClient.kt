package shop.voenix.spod

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
import io.ktor.client.request.parameter
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
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

/**
 * The one place in this backend that knows the print-on-demand partner's HTTP API. Eight calls go
 * out — five that produce an order (upload a design, create it, read it, confirm it, ask which
 * placements a product type offers for a design) and three that read the merchant's catalog (list
 * the articles, read a size chart, download one of the images those answers point at) — and
 * everything that can come back other than a usable answer becomes a bounded [SpodError].
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
 * timeout, so every call takes a [SpodAccess] and reads all three off it. One client therefore
 * serves every supplier — and paces all of them together, which is what a per-account rate limit
 * deserves the day a shop grows a second account.
 *
 * [download] is the one documented exception to the last rule (ADR 0003, decision 5): its URL comes
 * out of an answer the partner gave this shop, not out of [SpodEnvironment]. It is bounded instead
 * — https only, no token, images only, [MAX_IMAGE_BYTES] at most — and it is not paced, because a
 * CDN is not the rate-limited API.
 */
public class SpodClient
private constructor(
    private val client: HttpClient,
    private val nowMillis: () -> Long,
    private val pause: suspend (Long) -> Unit,
) : AutoCloseable {
    /**
     * The adapter a deployment runs: it builds its own client on the CIO engine, and because that
     * engine came from a factory rather than from a caller, Ktor owns it — [close] closes both.
     */
    public constructor() :
        this(HttpClient(CIO) { configureSpodClient() }, MONOTONIC_MILLIS, ::delay)

    /**
     * The same adapter on an [engine] somebody else supplied — a test's `MockEngine` — with the
     * clock and the sleeping function of the pacer as seams. Passing an engine *instance* leaves
     * Ktor's `manageEngine` off, so [close] closes this client but not the engine: whoever created
     * the engine keeps owning it.
     *
     * The configuration is the deployment's own, so a request made through this adapter carries the
     * very redirect rule and `expectSuccess` setting that a deployment sends.
     *
     * It is public because the consuming modules' own integration tests drive their submission and
     * sync stages against a `MockEngine` — the blessed test seam of this module, next to the
     * `createVatReader` kind of factory in `docs/dev/backend/conventions/module-architecture.md`.
     */
    public constructor(
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
    public suspend fun uploadDesign(
        access: SpodAccess,
        fileName: String,
        png: ByteArray,
    ): SpodResult<String> =
        call(access, "uploading a design") {
            client
                .post(access.url("/designs/upload")) {
                    spodRequest(access)
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
    public suspend fun createOrder(
        access: SpodAccess,
        request: SpodOrderRequest,
    ): SpodResult<String> =
        call(access, "creating an order") {
            client
                .post(access.url("/orders")) {
                    spodRequest(access)
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
    public suspend fun getOrder(
        access: SpodAccess,
        orderId: String,
    ): SpodResult<String> =
        call(access, "reading an order") {
            client
                .get(access.url("/orders/$orderId")) { spodRequest(access) }
                .answered { body -> JSON.decodeFromString<SpodOrderResponse>(body).state }
        }

    /** Turns the inert `NEW` order into a real one. This is the moment production is ordered. */
    public suspend fun confirmOrder(
        access: SpodAccess,
        orderId: String,
    ): SpodResult<Unit> =
        call(access, "confirming an order") {
            client
                .post(access.url("/orders/$orderId/confirm")) { spodRequest(access) }
                .answered { Unit }
        }

    /**
     * The placements this product type offers for this design. Asked only when the default
     * placement was refused, and answered with the plain hotspot names the partner uses.
     */
    public suspend fun availableHotspots(
        access: SpodAccess,
        productTypeId: Long,
        designId: String,
    ): SpodResult<List<String>> =
        call(access, "reading available hotspots") {
            val path = "/productTypes/$productTypeId/hotspots/design/$designId"
            client
                .get(access.url(path)) { spodRequest(access) }
                .answered { body ->
                    JSON.decodeFromString<SpodHotspotsResponse>(body).hotspots.map { hotspot ->
                        hotspot.name
                    }
                }
        }

    /**
     * One page of the merchant's backoffice articles — the source of truth for every synced t-shirt
     * (ADR 0003).
     *
     * Paging is the caller's: the answer carries the total [SpodCatalogPage.count], and the sync
     * asks for the next [offset] until it has seen them all. Doing it here would hide from the sync
     * the one thing it must know — whether the listing was *complete*, because only a complete
     * listing may deactivate what is missing from it.
     */
    public suspend fun articles(
        access: SpodAccess,
        limit: Int,
        offset: Int,
    ): SpodResult<SpodCatalogPage> =
        call(access, "listing articles") {
            client
                .get(access.url("/articles")) {
                    spodRequest(access)
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
                .answered { body -> JSON.decodeFromString<SpodCatalogPage>(body) }
        }

    /**
     * The size chart of one product type: an image the partner hosts, which this shop stores as a
     * URL and never as an upload of its own (issue #224, decision D2).
     */
    public suspend fun sizeChart(
        access: SpodAccess,
        productTypeId: Long,
    ): SpodResult<SpodSizeChart> =
        call(access, "reading a size chart") {
            client
                .get(access.url("/productTypes/$productTypeId/size-chart")) { spodRequest(access) }
                .answered { body -> JSON.decodeFromString<SpodSizeChart>(body) }
        }

    /**
     * Fetches one catalog image by the URL a previous answer of the partner carried — an example
     * image of a colour, or a size chart.
     *
     * Everything unusual about this call follows from where that URL comes from. It is partner
     * input, so it is bounded rather than trusted: only `https`, never a token (the CDN does not
     * ask for one, and a token sent to a host this adapter did not choose would be a token given
     * away), no redirect followed (the client is configured that way for every call), only an
     * answer whose content type is an image one, and at most [MAX_IMAGE_BYTES] read regardless of
     * what the answer announces. It is not paced either: the 60-per-minute budget belongs to the
     * API, and pacing a few hundred CDN images behind it would make a sync take minutes for
     * nothing.
     *
     * Only [timeoutSeconds] comes from the caller, and on a refusal at most the host is logged —
     * never the URL, never the body.
     *
     * A URL or an answer that is not a usable image becomes [UNUSABLE_IMAGE]; a partner that
     * refused or broke off becomes the same codes every other call produces.
     */
    public suspend fun download(url: String, timeoutSeconds: Int): SpodResult<SpodBinary> {
        val host = httpsHostOf(url)
        if (host == null) {
            logger.warn("SPOD image download refused: the answered URL is not an https URL")
            return UNUSABLE_IMAGE
        }
        return guarded("image download from $host") {
            client.get(url) { timeoutAfter(timeoutSeconds) }.image(host)
        }
    }

    override fun close() {
        client.close()
    }

    /** Holds no secret of its own — a [SpodAccess] does, and its own `toString` redacts it. */
    override fun toString(): String = "SpodClient(pacedEveryMillis=$MIN_REQUEST_INTERVAL_MILLIS)"

    /**
     * Runs one paced API call under [guarded], with [what] naming the call in this adapter's own
     * words and the destination id as the context that may be logged.
     */
    private suspend fun <T : Any> call(
        access: SpodAccess,
        what: String,
        step: suspend () -> SpodResult<T>,
    ): SpodResult<T> =
        guarded("destination ${access.destinationId}: $what") {
            pace()
            step()
        }

    /**
     * Turns every way a call can fail into a bounded [SpodError], with [context] naming it in this
     * adapter's own words — a destination and a call for the API, a host for a download.
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
    private suspend fun <T : Any> guarded(
        context: String,
        step: suspend () -> SpodResult<T>,
    ): SpodResult<T> =
        try {
            step()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SerializationException) {
            logger.error(
                "SPOD {} produced an answer that could not be read ({})",
                context,
                exception::class.simpleName,
            )
            SpodResult.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE, ambiguous = true)
        } catch (exception: HttpRequestTimeoutException) {
            logger.error(
                "SPOD {} did not answer within the configured timeout",
                context,
                exception,
            )
            SpodResult.Failed(SpodError.PROVIDER_UNAVAILABLE, ambiguous = true)
        } catch (exception: IOException) {
            logger.error("SPOD {} failed", context, exception)
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

    internal companion object {
        /**
         * The partner allows 60 requests per minute. One second would be exactly the limit and
         * therefore no margin at all; the extra 50 ms absorbs the scheduling jitter between reading
         * the clock and the request actually leaving.
         */
        const val MIN_REQUEST_INTERVAL_MILLIS: Long = 1_050

        const val ACCESS_TOKEN_HEADER: String = "X-SPOD-ACCESS-TOKEN"

        /**
         * How much of a catalog image is ever held in memory. It is the same 10 MiB a visitor may
         * upload, because a mockup of a shirt has no business being larger than the picture that
         * gets printed on it.
         */
        const val MAX_IMAGE_BYTES: Int = 10 * 1024 * 1024

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
    }
}

/**
 * The one logger of this adapter — a top-level one, because the response judgements below are
 * top-level functions and log exactly the way the client itself does.
 */
private val logger: Logger = LoggerFactory.getLogger(SpodClient::class.java)

/** How much of a body is read in one go. */
private const val CHUNK_BYTES = 64 * 1024

private const val MILLIS_PER_SECOND = 1_000L

/**
 * A download that answered something other than a usable image: not `https`, not an image, or
 * larger than the cap. The caller acts on all three the same way — variant inactive, bounded
 * warning — so the persisted vocabulary needs no code of its own for them. `ambiguous` says nothing
 * here: a download takes no effect that could be in doubt.
 */
private val UNUSABLE_IMAGE =
    SpodResult.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE, ambiguous = false)

/**
 * One answer judged: a refusal status becomes a bounded failure, a success is handed to [read], and
 * a `null` from [read] is an answer that arrived without the field this adapter needs.
 *
 * A refusal is judged by [refusalOf], and its body is never read — whatever it said stays with the
 * partner.
 */
private suspend fun <T : Any> HttpResponse.answered(read: (String) -> T?): SpodResult<T> {
    if (!status.isSuccess()) {
        bodyAsChannel().cancel()
        logger.warn("SPOD refused a request with status {}", status.value)
        return refusalOf(status)
    }
    val value = read(bodyAsText())
    return if (value == null) {
        SpodResult.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE, ambiguous = true)
    } else {
        SpodResult.Answered(value)
    }
}

/**
 * One image answer judged: the same refusal families as [answered], then the two bounds that exist
 * because this URL came from the partner — the answer has to *be* an image, and only
 * [SpodClient.MAX_IMAGE_BYTES] of it are ever held, no matter what its `Content-Length` claims.
 * [host] is all of the URL that may reach a log line.
 */
private suspend fun HttpResponse.image(host: String): SpodResult<SpodBinary> {
    val contentType = contentType()?.withoutParameters()
    if (
        !status.isSuccess() ||
            contentType == null ||
            contentType.contentType != ContentType.Image.Any.contentType
    ) {
        bodyAsChannel().cancel()
        return imageRefusal(host)
    }
    val bytes = bodyAsChannel().readCapped()
    return if (bytes == null) {
        logger.warn(
            "SPOD image download from {} is larger than {} bytes",
            host,
            SpodClient.MAX_IMAGE_BYTES,
        )
        UNUSABLE_IMAGE
    } else {
        SpodResult.Answered(SpodBinary(bytes = bytes, contentType = contentType.toString()))
    }
}

/** Which of the two the answer earned: the partner's refusal, or "that was not an image". */
private fun HttpResponse.imageRefusal(host: String): SpodResult.Failed =
    if (status.isSuccess()) {
        logger.warn("SPOD image download from {} did not answer an image", host)
        UNUSABLE_IMAGE
    } else {
        logger.warn("SPOD image download from {} was refused with status {}", host, status.value)
        refusalOf(status)
    }

/**
 * The bytes of this channel, or `null` once they turn out to exceed [SpodClient.MAX_IMAGE_BYTES] —
 * which point the rest is not drained but cancelled: this is an answer, not a request, and refusing
 * to keep paying for it is the point of the cap.
 *
 * The close cause is rethrown for the same reason the platform's `readChunks` does it: a channel
 * that was cut off mid-transfer ends exactly like one that was complete, and taking half an image
 * for a whole one would store a broken picture.
 */
private suspend fun ByteReadChannel.readCapped(): ByteArray? {
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (true) {
        val count = readAvailable(chunk, 0, chunk.size)
        if (count <= 0) break
        if (collected.size() + count > SpodClient.MAX_IMAGE_BYTES) {
            cancel()
            return null
        }
        collected.write(chunk, 0, count)
    }
    closedCause?.let { cause -> throw cause }
    return collected.toByteArray()
}

private fun HttpRequestBuilder.spodRequest(access: SpodAccess) {
    header(SpodClient.ACCESS_TOKEN_HEADER, access.accessToken)
    timeoutAfter(access.timeoutSeconds)
}

private fun HttpRequestBuilder.timeoutAfter(seconds: Int) {
    timeout {
        val millis = seconds.toLong() * MILLIS_PER_SECOND
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

/** Where a call goes, derived from the destination's environment and never from a column. */
private fun SpodAccess.url(path: String): String = environment.baseUrl + path

/**
 * The two refusal families, kept apart because the order-creation protocol depends on the
 * difference. A `4xx` is the partner saying "I did not do this" — the outcome is *known*, and
 * re-creating later is safe. A `5xx` is the partner saying nothing usable at all after having
 * received the request, so the order may exist and the outcome is ambiguous.
 */
private fun refusalOf(status: HttpStatusCode): SpodResult.Failed {
    val serverSide = status.value >= HttpStatusCode.InternalServerError.value
    val error =
        when {
            status == HttpStatusCode.TooManyRequests -> SpodError.RATE_LIMITED
            serverSide -> SpodError.PROVIDER_UNAVAILABLE
            else -> SpodError.REFUSED
        }
    return SpodResult.Failed(error, ambiguous = serverSide)
}

/**
 * The host of [url] when it is an `https` URL, and `null` for everything else — a `http` URL, a
 * relative one, anything that is not a URL at all.
 *
 * The host is picked apart by hand rather than parsed, so that no exception of a URL parser has to
 * be judged here, and any user info is dropped: what this function answers is logged, and a
 * partner-written `https://secret@host/` must not put that `secret` in a log line.
 */
private fun httpsHostOf(url: String): String? {
    if (!url.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
    return url.substring(HTTPS_PREFIX.length)
        .substringBefore('/')
        .substringAfterLast('@')
        .substringBefore(':')
        .ifEmpty { null }
}

private const val HTTPS_PREFIX = "https://"

/**
 * The typed outcome of one provider call.
 *
 * [Failed] carries only a bounded [SpodError] — a provider body, a token, or a URL can never reach
 * a persisted error column by construction — plus [Failed.ambiguous], the one fact the worker's
 * idempotency protocol depends on.
 */
public sealed interface SpodResult<out T> {
    public data class Answered<T>(public val value: T) : SpodResult<T>

    /**
     * @param ambiguous whether the call may have taken effect without saying so. `false` only for a
     *   refusal the partner stated explicitly; a timeout, a reset, an unreadable answer, and any
     *   `5xx` are all `true`, because the safe assumption for an order creation is "it may exist".
     */
    public data class Failed(public val error: SpodError, public val ambiguous: Boolean) :
        SpodResult<Nothing>
}

/** The bounded vocabulary of provider-call failures; the names are the persisted error codes. */
public enum class SpodError {
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
public data class SpodOrderRequest(
    public val externalOrderReference: String,
    public val email: String,
    public val phone: String,
    public val shipping: SpodShipping,
    public val oneTimeItems: List<SpodOneTimeItem>,
    public val orderItems: List<String> = emptyList(),
    public val state: String = "NEW",
)

@Serializable
public data class SpodShipping(
    public val address: SpodAddress,
    /**
     * Standard shipping, preset at creation; the customer never chooses (issue #205, decision 5).
     */
    public val preferredType: String = "STANDARD",
)

@Serializable
public data class SpodAddress(
    public val firstName: String,
    public val lastName: String,
    public val street: String,
    public val city: String,
    public val country: String,
    public val zipCode: String,
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
public data class SpodOneTimeItem(
    public val productTypeId: Long,
    public val quantityItems: List<SpodQuantityItem>,
    public val configurations: List<SpodConfiguration>,
)

@Serializable
public data class SpodQuantityItem(
    public val quantity: Int,
    public val sizeId: Long,
    public val appearanceId: Long,
)

/** Where the design sits: the view of the garment and one of that view's named hotspots. */
@Serializable
public data class SpodConfiguration(
    public val image: SpodConfigurationImage,
    public val view: String,
    public val hotspot: String,
)

@Serializable public data class SpodConfigurationImage(public val designId: String)

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

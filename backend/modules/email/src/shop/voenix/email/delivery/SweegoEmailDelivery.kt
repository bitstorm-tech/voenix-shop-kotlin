package shop.voenix.email.delivery

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import shop.voenix.email.EmailSettings
import shop.voenix.email.rendering.RenderedEmail

internal class SweegoEmailDelivery
private constructor(
    private val settings: EmailSettings,
    private val client: HttpClient,
) : EmailDelivery, AutoCloseable {
    /**
     * The adapter a deployment runs: it builds its own client on the CIO engine, and because that
     * engine came from a factory rather than from a caller, Ktor owns it — [close] closes both.
     */
    constructor(
        settings: EmailSettings
    ) : this(settings, HttpClient(CIO) { configureSweegoClient() })

    /**
     * The same adapter on an [engine] somebody else supplied — a test's `MockEngine`. Passing an
     * engine *instance* leaves Ktor's `manageEngine` off, so [close] closes this client but not the
     * engine: whoever created the engine keeps owning it.
     *
     * The configuration is the deployment's own, so a request made through this adapter carries the
     * very timeouts, the redirect rule and the `expectSuccess` setting that a deployment sends.
     */
    constructor(
        settings: EmailSettings,
        engine: HttpClientEngine,
    ) : this(settings, HttpClient(engine) { configureSweegoClient() })

    override suspend fun deliver(
        email: RenderedEmail,
        campaignId: String?,
    ): EmailDeliveryResult {
        check(settings.enabled) { "Sweego delivery must not be called while email is disabled" }
        val sender = checkNotNull(settings.sender)
        return try {
            val response =
                client.post(settings.sendUrl) {
                    header("Api-Key", settings.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        SweegoSendRequest(
                            recipients =
                                listOf(
                                    SweegoSendRequest.Recipient(
                                        email = email.recipient.value,
                                        name = email.recipientName.orEmpty(),
                                    )
                                ),
                            from =
                                SweegoSendRequest.Sender(
                                    email = sender.value,
                                    name = settings.fromName,
                                ),
                            subject = email.subject,
                            messageHtml = email.html,
                            messageText = email.text,
                            campaignId = campaignId,
                        )
                    )
                }
            response.body<ByteArray>()
            if (response.status.value in SUCCESS_STATUS_RANGE) {
                EmailDeliveryResult.Accepted
            } else {
                EmailDeliveryResult.Failed(code = "PROVIDER_HTTP_${response.status.value}")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: HttpRequestTimeoutException) {
            timeoutFailure("REQUEST_TIMEOUT")
        } catch (_: ConnectTimeoutException) {
            timeoutFailure("CONNECT_TIMEOUT")
        } catch (_: SocketTimeoutException) {
            timeoutFailure("SOCKET_TIMEOUT")
        } catch (_: SerializationException) {
            EmailDeliveryResult.Failed(code = "REQUEST_SERIALIZATION_FAILED")
        } catch (_: IOException) {
            EmailDeliveryResult.Failed(code = "PROVIDER_UNAVAILABLE")
        }
    }

    override fun close() {
        client.close()
    }

    private fun timeoutFailure(code: String): EmailDeliveryResult.Failed =
        EmailDeliveryResult.Failed(code = code)

    private companion object {
        val SUCCESS_STATUS_RANGE: IntRange = MINIMUM_SUCCESS_STATUS..MAXIMUM_SUCCESS_STATUS

        const val MINIMUM_SUCCESS_STATUS = 200
        const val MAXIMUM_SUCCESS_STATUS = 299
    }
}

/**
 * Everything about the client that is a decision rather than an engine.
 *
 * It is a function of its own for one reason: both constructors of [SweegoEmailDelivery] apply it,
 * so the client a test drives and the client a deployment runs are configured by the same lines. A
 * test does not rebuild this configuration — it receives it, and reads the timeouts back off a
 * request the adapter itself made. A client whose timeouts silently disappeared would look exactly
 * like this one until the day Sweego stops answering.
 *
 * Redirects are not followed: Sweego's send endpoint never answers with one, so a redirect is a
 * refusal to be reported — as `PROVIDER_HTTP_302` like any other unsuccessful status — not a route
 * to be walked. Walking it would replay the whole message, the API key header included, against a
 * URL this adapter never chose. Ktor already refuses to walk a redirect on anything but a `GET` or
 * `HEAD` (`HttpRedirectConfig.checkHttpMethod` is on by default), so for this adapter's one `POST`
 * the flag is a second lock rather than the first; it is set anyway because the reason is the
 * adapter's, not the plugin's. `expectSuccess` stays off, so a refusal is a status this adapter
 * judges rather than an exception it catches.
 *
 * The `Json` instance is built here because this is its only use: `encodeDefaults` sends the
 * `channel`, `provider` and `campaign-type` constants Sweego requires, and `explicitNulls = false`
 * omits an absent campaign id rather than transmitting it as `null`.
 */
private fun HttpClientConfig<*>.configureSweegoClient() {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
            }
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
}

private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val CONNECT_TIMEOUT_MILLIS = 10_000L
private const val SOCKET_TIMEOUT_MILLIS = 30_000L

@Serializable
internal data class SweegoSendRequest(
    val channel: String = "email",
    val provider: String = "sweego",
    val recipients: List<Recipient>,
    val from: Sender,
    val subject: String,
    @SerialName("message-html") val messageHtml: String,
    @SerialName("message-txt") val messageText: String,
    @SerialName("campaign-type") val campaignType: String = "transac",
    @SerialName("campaign-id") val campaignId: String? = null,
) {
    @Serializable internal data class Recipient(val email: String, val name: String)

    @Serializable internal data class Sender(val email: String, val name: String)
}

package shop.voenix.email

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class SweegoEmailDelivery(
    private val settings: EmailSettings,
    private val client: HttpClient = createClient(),
) : EmailDelivery, AutoCloseable {
    override suspend fun deliver(
        email: RenderedEmail,
        campaignId: String?,
    ): EmailDeliveryResult {
        check(settings.enabled) { "Sweego delivery must not be called while email is disabled" }
        val sender = checkNotNull(settings.sender)
        return try {
            val response =
                client.post(SWEEGO_SEND_URL) {
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
                EmailDeliveryResult.Failed(
                    code = "PROVIDER_HTTP_${response.status.value}",
                    safeMessage = "Email provider returned HTTP ${response.status.value}",
                    retryAfter = response.retryAfter(),
                )
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
            EmailDeliveryResult.Failed(
                code = "REQUEST_SERIALIZATION_FAILED",
                safeMessage = "Email provider request could not be serialized",
            )
        } catch (_: IOException) {
            EmailDeliveryResult.Failed(
                code = "PROVIDER_UNAVAILABLE",
                safeMessage = "Email provider request failed before acceptance was confirmed",
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun io.ktor.client.statement.HttpResponse.retryAfter(): Duration? {
        if (
            status != HttpStatusCode.TooManyRequests && status != HttpStatusCode.ServiceUnavailable
        ) {
            return null
        }
        val value =
            headers[HttpHeaders.RetryAfter]?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val duration =
            value.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
                ?: try {
                    Duration.between(
                        Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant(),
                    )
                } catch (_: DateTimeParseException) {
                    null
                }
        return duration?.coerceIn(Duration.ZERO, MAX_RETRY_AFTER)
    }

    private fun timeoutFailure(code: String): EmailDeliveryResult.Failed =
        EmailDeliveryResult.Failed(
            code = code,
            safeMessage = "Email provider acceptance was not confirmed before the timeout",
        )

    private companion object {
        const val SWEEGO_SEND_URL = "https://api.sweego.io/send"
        val SUCCESS_STATUS_RANGE: IntRange = MINIMUM_SUCCESS_STATUS..MAXIMUM_SUCCESS_STATUS
        val MAX_RETRY_AFTER: Duration = Duration.ofHours(24)

        fun createClient(): HttpClient =
            HttpClient(CIO) {
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

        const val MINIMUM_SUCCESS_STATUS = 200
        const val MAXIMUM_SUCCESS_STATUS = 299
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val SOCKET_TIMEOUT_MILLIS = 30_000L
    }
}

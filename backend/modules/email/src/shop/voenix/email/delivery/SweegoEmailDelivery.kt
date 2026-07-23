package shop.voenix.email.delivery

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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import shop.voenix.email.EmailSettings
import shop.voenix.email.rendering.RenderedEmail

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

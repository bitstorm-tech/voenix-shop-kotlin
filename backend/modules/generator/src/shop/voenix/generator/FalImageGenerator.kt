package shop.voenix.generator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.cancel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The one place in this backend that knows fal.ai: the uploaded image goes out as a data URI, the
 * generated one comes back from a URL the provider answers with.
 *
 * Every way this can go wrong ends in `null`, which the service turns into one `502`. That is not
 * laziness about error handling — it is the [ImageGenerator] contract. A refusal, an empty answer,
 * an unreadable one, an unreachable host and a failed download all mean the same thing to a
 * visitor, so the distinction lives in the log instead of in the type. Only a
 * [CancellationException] passes through: the request ending is not a provider failure.
 *
 * The generation call is never retried. A retry would pay the provider twice for a call that may
 * well have succeeded on their side, and this endpoint costs money on every attempt.
 */
internal class FalImageGenerator(
    private val settings: GeneratorSettings,
    private val client: HttpClient = createClient(),
) : ImageGenerator, AutoCloseable {
    override suspend fun generate(
        image: RawImage,
        prompt: String,
    ): RawImage? = requestGeneration(image, prompt)?.let { generated -> download(generated) }

    /**
     * Sends the one request this provider understands and returns the first image of its answer.
     *
     * The body is read before the status is judged so the connection is always drained, and the
     * provider's own error text is deliberately not part of any log message beyond the status: an
     * error body is provider output and may quote back what was sent to it, including the key.
     */
    private suspend fun requestGeneration(
        image: RawImage,
        prompt: String,
    ): FalEditResponse.Image? =
        upstream("The fal.ai generation call failed") {
            val response =
                client.post(settings.apiUrl) {
                    header(HttpHeaders.Authorization, "Key ${settings.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        FalEditRequest(
                            imageUrls = listOf(image.dataUri()),
                            prompt = prompt,
                            numImages = IMAGE_COUNT,
                            aspectRatio = ASPECT_RATIO,
                        )
                    )
                }
            val payload = response.body<ByteArray>().decodeToString()
            if (!response.status.isSuccess()) {
                logger.error("fal.ai refused a generation with status {}", response.status.value)
                return@upstream null
            }
            JSON.decodeFromString<FalEditResponse>(payload).images.firstOrNull().also { generated ->
                if (generated == null) logger.error("fal.ai answered without a generated image")
            }
        }

    /**
     * Fetches the generated image itself. Three things are true of this request and none of them
     * are true of the first one: it goes to a host fal.ai names rather than to fal.ai, it carries
     * no credential, and what comes back is bytes of unknown size and unknown type.
     *
     * So the URL must be HTTPS — a provider answer must not talk this server into a plaintext fetch
     * — the API key stays behind, and the body is collected only up to [MAX_IMAGE_BYTES], the same
     * size a visitor may upload.
     */
    private suspend fun download(image: FalEditResponse.Image): RawImage? =
        upstream("The generated image could not be downloaded") {
            val url = httpsUrl(image.url) ?: return@upstream null
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                logger.error(
                    "Downloading a generated image answered status {}",
                    response.status.value,
                )
                response.bodyAsChannel().cancel()
                return@upstream null
            }
            response.readLimitedBytes()?.let { bytes ->
                RawImage(bytes, resultContentType(image.contentType))
            }
        }

    override fun close() {
        client.close()
    }

    /**
     * Runs one provider step and reports every way it can fail as the absent image the
     * [ImageGenerator] contract asks for, with [what] naming the step in the log.
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
            logger.error("$what: the provider answer could not be read", exception)
            null
        } catch (exception: IOException) {
            logger.error("$what: the provider could not be reached", exception)
            null
        }

    /** The result URL, or `null` when it is unparseable or not an HTTPS URL. */
    private fun httpsUrl(value: String): Url? {
        val url = runCatching { Url(value) }.getOrNull()
        if (url == null || !url.protocol.name.equals(HTTPS_SCHEME, ignoreCase = true)) {
            logger.error("fal.ai returned a result URL that is not an HTTPS URL")
            return null
        }
        return url
    }

    /**
     * The body of [this], or `null` once it turns out to be larger than [MAX_IMAGE_BYTES] — the
     * same size a visitor may upload, because a generated image is not allowed to be bigger than
     * the picture it was generated from.
     *
     * The bytes are collected chunk by chunk and the collecting stops at the limit, so what a
     * provider announces about the size never decides how much of it this server holds.
     */
    private suspend fun HttpResponse.readLimitedBytes(): ByteArray? {
        val channel = bodyAsChannel()
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (!channel.exhausted()) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read <= 0) break
            if (collected.size() + read > MAX_IMAGE_BYTES) {
                logger.error("The generated image is larger than $MAX_IMAGE_BYTES bytes")
                channel.cancel()
                return null
            }
            collected.write(chunk, 0, read)
        }
        return collected.toByteArray()
    }

    /** How this provider wants the input image: inline in the request, not as a URL. */
    private fun RawImage.dataUri(): String =
        "data:$contentType;base64,${Base64.getEncoder().encodeToString(bytes)}"

    /**
     * The content type the response is served with. What the provider reports is used only when it
     * is one of the types this shop serves; anything else — an exotic type, a missing one — becomes
     * `image/jpeg`, which is what the legacy application did for a missing one.
     */
    private fun resultContentType(reported: String?): String {
        val normalized = reported?.trim()?.lowercase().orEmpty()
        return if (normalized in ALLOWED_IMAGE_CONTENT_TYPES) {
            normalized
        } else {
            DEFAULT_RESULT_CONTENT_TYPE
        }
    }

    /** What fal.ai is asked for. The names are the provider's, hence the `snake_case`. */
    @Serializable
    private data class FalEditRequest(
        @SerialName("image_urls") val imageUrls: List<String>,
        val prompt: String,
        @SerialName("num_images") val numImages: Int,
        @SerialName("aspect_ratio") val aspectRatio: String,
    )

    /**
     * What fal.ai answers. Every field has a default, so an answer that simply omits the images —
     * the provider's way of returning nothing — deserializes into an empty list instead of
     * throwing, and lands in the same absent case as everything else.
     */
    @Serializable
    private data class FalEditResponse(val images: List<Image> = emptyList()) {
        @Serializable
        data class Image(
            val url: String = "",
            @SerialName("content_type") val contentType: String? = null,
        )
    }

    private companion object {
        /**
         * Unknown fields are ignored on purpose: a provider that adds a field to its answer must
         * not break image generation for every customer.
         */
        val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Redirects are followed, because the generated image usually lives behind a CDN that
         * redirects; the fal.ai call itself does not redirect. `expectSuccess` stays off, so a
         * refusal is a status this adapter judges rather than an exception it catches.
         */
        fun createClient(): HttpClient =
            HttpClient(CIO) {
                expectSuccess = false
                followRedirects = true
                install(ContentNegotiation) { json(JSON) }
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }

        /** Kept from the legacy application; an open product question, not a technical one. */
        const val ASPECT_RATIO = "16:9"
        const val IMAGE_COUNT = 1
        const val DEFAULT_RESULT_CONTENT_TYPE = "image/jpeg"
        const val HTTPS_SCHEME = "https"
        const val CHUNK_BYTES = 64 * 1024

        /**
         * Generating an image takes far longer than an ordinary API call, hence the two minutes.
         */
        const val REQUEST_TIMEOUT_MILLIS = 120_000L
        const val CONNECT_TIMEOUT_MILLIS = 10_000L

        val logger: Logger = LoggerFactory.getLogger(FalImageGenerator::class.java)
    }
}

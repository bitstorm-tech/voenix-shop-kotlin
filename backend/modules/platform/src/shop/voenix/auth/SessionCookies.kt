package shop.voenix.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.sessions.CookieConfiguration
import io.ktor.server.sessions.SessionProvider
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.SessionTrackerByValue
import io.ktor.server.sessions.SessionTransport
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.SessionsConfig
import io.ktor.server.sessions.transformRead
import io.ktor.server.sessions.transformWrite
import io.ktor.util.date.GMTDate
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlin.reflect.KClass

internal class SameAsRequestCookieTransport(
    private val name: String,
    private val configuration: CookieConfiguration,
    private val transformers: List<SessionTransportTransformer>,
) : SessionTransport {
    override fun receive(call: ApplicationCall): String? =
        transformers.transformRead(call.request.cookies[name, configuration.encoding])

    override fun send(
        call: ApplicationCall,
        value: String,
    ) {
        call.response.cookies.append(cookie(call, transformers.transformWrite(value)))
    }

    override fun clear(call: ApplicationCall) {
        call.response.cookies.append(
            cookie(
                call = call,
                value = "",
                maxAge = 0,
                expires = GMTDate.START,
            )
        )
    }

    private fun cookie(
        call: ApplicationCall,
        value: String,
        maxAge: Int? = null,
        expires: GMTDate? = null,
    ): Cookie =
        Cookie(
            name = name,
            value = value,
            encoding = configuration.encoding,
            maxAge = maxAge,
            expires = expires,
            domain = configuration.domain,
            path = configuration.path,
            secure = call.request.origin.scheme.equals("https", ignoreCase = true),
            httpOnly = configuration.httpOnly,
            extensions = configuration.extensions,
        )
}

internal fun <S : Any> SessionsConfig.sameAsRequestCookie(
    name: String,
    sessionType: KClass<S>,
    serializer: SessionSerializer<S>,
    transformer: SessionTransportTransformer,
) {
    val configuration =
        CookieConfiguration().apply {
            path = "/"
            httpOnly = true
            maxAgeInSeconds = null
            extensions["SameSite"] = "Lax"
        }
    register(
        SessionProvider(
            name = name,
            type = sessionType,
            transport = SameAsRequestCookieTransport(name, configuration, listOf(transformer)),
            tracker = SessionTrackerByValue(sessionType, serializer),
            sendOnlyIfModified = true,
        )
    )
}

internal object SessionCookieEncryption {
    fun transformer(
        sessionSecret: String,
        purpose: String,
    ): SessionTransportTransformerEncrypt {
        val encryptionKey = digest("$purpose:encryption:$sessionSecret").copyOf(16)
        val signingKey = digest("$purpose:signing:$sessionSecret")
        return SessionTransportTransformerEncrypt(
            encryptionKeySpec = SecretKeySpec(encryptionKey, "AES"),
            signKeySpec = SecretKeySpec(signingKey, "HmacSHA256"),
        )
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}

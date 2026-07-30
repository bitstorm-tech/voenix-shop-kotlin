package shop.voenix.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.security.SecureRandom
import java.util.Base64

public class GuestTokens(settings: AuthSettings) {
    private val transformer = SessionCookieEncryption.transformer(settings.sessionSecret, "guest")

    /**
     * Returns the guest token of an existing, decryptable `voenix.guest` cookie, or `null` when the
     * request carries no usable cookie. Never sets a cookie: read paths must not create guests.
     */
    public fun tryGet(call: ApplicationCall): String? =
        call.request.cookies[COOKIE_NAME]?.let(transformer::transformRead)

    public fun getOrCreate(call: ApplicationCall): String {
        val existing = tryGet(call)
        if (existing != null) return existing

        val token = newToken()
        call.response.cookies.append(
            Cookie(
                name = COOKIE_NAME,
                value = transformer.transformWrite(token),
                maxAge = COOKIE_MAX_AGE_SECONDS,
                path = COOKIE_PATH,
                secure = call.request.origin.scheme.equals("https", ignoreCase = true),
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            )
        )
        return token
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        const val COOKIE_NAME = "voenix.guest"
        const val COOKIE_PATH = "/api"
        const val COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60
        const val TOKEN_BYTES = 48

        val secureRandom = SecureRandom()
    }
}

package shop.voenix.http

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class FrontendBaseUrlTest {
    @Test
    fun `startup fails fast when the frontend base url is missing or blank`() {
        assertFailsWith<IllegalStateException> { FrontendBaseUrl.from(MapApplicationConfig()) }
        assertFailsWith<IllegalStateException> {
            FrontendBaseUrl.from(MapApplicationConfig("frontend.baseUrl" to "   "))
        }
    }

    @Test
    fun `the frontend base url must be an absolute http url`() {
        assertFailsWith<IllegalArgumentException> { FrontendBaseUrl("not a url") }
        assertFailsWith<IllegalArgumentException> { FrontendBaseUrl("/relative/path") }
        assertFailsWith<IllegalArgumentException> { FrontendBaseUrl("ftp://shop.example.com") }
    }

    @Test
    fun `https is required outside local environments`() {
        assertFailsWith<IllegalArgumentException> { FrontendBaseUrl("http://shop.example.com") }

        assertEquals("https://shop.example.com", FrontendBaseUrl("https://shop.example.com").value)
        assertEquals(
            "http://localhost:5173",
            FrontendBaseUrl("http://localhost:5173/").value,
            "local development may use HTTP and trailing slashes are trimmed",
        )
        assertEquals("http://127.0.0.1:5173", FrontendBaseUrl("http://127.0.0.1:5173").value)
    }

    @Test
    fun `the configured value is read from the frontend block`() {
        assertEquals(
            FrontendBaseUrl("https://shop.example.com"),
            FrontendBaseUrl.from(
                MapApplicationConfig("frontend.baseUrl" to "https://shop.example.com/")
            ),
            "one setting for the whole application, normalized once",
        )
    }
}

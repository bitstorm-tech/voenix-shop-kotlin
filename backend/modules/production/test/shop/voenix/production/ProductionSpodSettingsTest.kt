package shop.voenix.production

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The print-on-demand configuration block: when it exists, what it refuses, and what it never
 * prints.
 *
 * The refusals matter more than the defaults here. The secret is the whole protection of a route
 * that ships jobs without a session, and the alert address is where every state this shop cannot
 * resolve by itself ends up — a deployment that starts with half of the pair would look healthy and
 * silently drop the one message a human has to see.
 */
internal class ProductionSpodSettingsTest {
    @Test
    fun `a deployment without a print-on-demand supplier configures nothing`() {
        assertNull(ProductionSpodSettings.from(config(secret = "", alertEmail = "")))
        assertNull(ProductionSpodSettings.from(MapApplicationConfig()))
    }

    @Test
    fun `both values are read and trimmed`() {
        val settings =
            ProductionSpodSettings.from(config(secret = "  $SECRET  ", alertEmail = " ops@x.de "))

        assertEquals(SECRET, settings?.webhookSecret)
        assertEquals("ops@x.de", settings?.alertEmail)
    }

    @Test
    fun `a secret shorter than 32 characters is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProductionSpodSettings.from(config(secret = "short", alertEmail = "ops@x.de"))
            }

        assertEquals("SPOD webhook secret must be at least 32 characters", failure.message)
    }

    @Test
    fun `half a block is a configuration mistake, in both directions`() {
        assertFailsWith<IllegalArgumentException> {
            ProductionSpodSettings.from(config(secret = SECRET, alertEmail = ""))
        }
        assertFailsWith<IllegalArgumentException> {
            ProductionSpodSettings.from(config(secret = "", alertEmail = "ops@x.de"))
        }
    }

    @Test
    fun `the secret never reaches a log line`() {
        val rendered =
            ProductionSettings(
                    artifactRoot = java.nio.file.Path.of("/tmp/artifacts"),
                    spod = ProductionSpodSettings(SECRET, "ops@x.de"),
                )
                .toString()

        assertTrue(rendered.contains("[REDACTED]"), rendered)
        assertTrue(rendered.contains("ops@x.de"), rendered)
        assertTrue(!rendered.contains(SECRET), "the secret must not be printed")
    }

    private fun config(secret: String, alertEmail: String): MapApplicationConfig =
        MapApplicationConfig(
            "production.spod.webhookSecret" to secret,
            "production.spod.alertEmail" to alertEmail,
        )

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
    }
}

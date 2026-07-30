package shop.voenix.generator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class GeneratorSettingsTest {
    @Test
    fun `a deployment without dummy mode needs an api key`() {
        val failure = assertFailsWith<IllegalArgumentException> { GeneratorSettings(apiKey = "  ") }

        assertEquals("Generator API key is required unless dummy mode is enabled", failure.message)
    }

    @Test
    fun `dummy mode needs no api key`() {
        val settings = GeneratorSettings(dummyMode = true)

        assertTrue(settings.dummyMode)
        assertEquals("", settings.apiKey)
    }

    @Test
    fun `the api key is trimmed`() {
        assertEquals("fal-key", GeneratorSettings(apiKey = " fal-key ").apiKey)
    }

    @Test
    fun `the api url must be absolute`() {
        assertFailsWith<IllegalArgumentException> {
            GeneratorSettings(dummyMode = true, apiUrl = "fal.run/edit")
        }
    }

    @Test
    fun `the string representation never carries the key`() {
        val settings = GeneratorSettings(apiKey = "super-secret-key")

        val rendered = settings.toString()

        assertFalse(rendered.contains("super-secret-key"), "Settings are logged; keys are not")
        assertTrue(rendered.contains("[REDACTED]"))
    }
}

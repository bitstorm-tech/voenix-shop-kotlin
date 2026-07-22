package shop.voenix.production

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ProductionDestinationInputValidationTest {
    @Test
    fun `a complete destination input is valid`() {
        assertEquals(emptyMap(), validInput.validate())
    }

    @Test
    fun `an empty input reports every required field`() {
        assertEquals(
            setOf(
                "supplierId",
                "channel",
                "label",
                "host",
                "username",
                "hostKeyFingerprint",
                "timeoutSeconds",
            ),
            ProductionDestinationInput().validate().keys,
        )
    }

    @Test
    fun `identifiers and channel are checked for shape`() {
        assertEquals(
            listOf("SupplierId must be positive"),
            validInput.copy(supplierId = 0).validate().getValue("supplierId"),
        )
        assertEquals(
            listOf("Channel must be one of: SFTP"),
            validInput.copy(channel = "FTP").validate().getValue("channel"),
        )
    }

    @Test
    fun `port and timeout must stay within sensible bounds`() {
        assertEquals(emptyMap(), validInput.copy(port = 1).validate())
        assertEquals(emptyMap(), validInput.copy(port = 65535).validate())
        assertEquals(
            listOf("Port must be between 1 and 65535"),
            validInput.copy(port = 0).validate().getValue("port"),
        )
        assertEquals(
            listOf("Port must be between 1 and 65535"),
            validInput.copy(port = 65536).validate().getValue("port"),
        )

        assertEquals(emptyMap(), validInput.copy(timeoutSeconds = 1).validate())
        assertEquals(emptyMap(), validInput.copy(timeoutSeconds = 3600).validate())
        assertEquals(
            listOf("TimeoutSeconds must be between 1 and 3600"),
            validInput.copy(timeoutSeconds = 0).validate().getValue("timeoutSeconds"),
        )
        assertEquals(
            listOf("TimeoutSeconds must be between 1 and 3600"),
            validInput.copy(timeoutSeconds = 3601).validate().getValue("timeoutSeconds"),
        )
    }

    @Test
    fun `optional notification email must have a valid shape`() {
        assertEquals(emptyMap(), validInput.copy(notificationEmail = null).validate())
        assertEquals(emptyMap(), validInput.copy(notificationEmail = "  ").validate())
        assertEquals(
            listOf("NotificationEmail must be a valid email address"),
            validInput
                .copy(notificationEmail = "not-an-email")
                .validate()
                .getValue("notificationEmail"),
        )
    }

    @Test
    fun `text fields are bounded`() {
        val overlong = "x".repeat(256)
        assertEquals(
            listOf("Label must be at most 255 characters"),
            validInput.copy(label = overlong).validate().getValue("label"),
        )
        assertEquals(
            listOf("Password must be at most 255 characters"),
            validInput.copy(password = overlong).validate().getValue("password"),
        )
        assertEquals(
            listOf("RemotePath must be at most 1024 characters"),
            validInput.copy(remotePath = "/" + "x".repeat(1024)).validate().getValue("remotePath"),
        )
    }

    @Test
    fun `the password never appears in the input string representation`() {
        val input = validInput.copy(password = "super-secret")
        assertFalse(input.toString().contains("super-secret"))
        assertTrue(input.toString().contains("[redacted]"))
        assertTrue(validInput.copy(password = null).toString().contains("password=null"))
    }

    private companion object {
        val validInput =
            ProductionDestinationInput(
                supplierId = 1,
                channel = "SFTP",
                label = "Producer drop",
                enabled = true,
                host = "sftp.example.test",
                port = 22,
                username = "voenix",
                password = "super-secret",
                hostKeyFingerprint = "SHA256:0123456789abcdef",
                remotePath = "/upload",
                timeoutSeconds = 30,
                notificationEmail = "producer@example.test",
                notificationName = "Producer",
            )
    }
}

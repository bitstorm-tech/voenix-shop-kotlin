package shop.voenix.production

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import shop.voenix.production.spod.SpodEnvironment

internal class ProductionDestinationInputValidationTest {
    @Test
    fun `a complete destination input is valid for both channels`() {
        assertEquals(emptyMap(), validSftpInput.validate())
        assertEquals(emptyMap(), validSpodInput.validate())
    }

    @Test
    fun `an empty input reports every required field it can decide on`() {
        assertEquals(
            setOf("supplierId", "channel", "label"),
            ProductionDestinationInput().validate().keys,
            "an unknown channel makes the detail blocks undecidable",
        )
    }

    @Test
    fun `an empty detail block reports every required detail field`() {
        assertEquals(
            setOf(
                "sftp.host",
                "sftp.username",
                "sftp.hostKeyFingerprint",
                "sftp.timeoutSeconds",
            ),
            validSftpInput.copy(sftp = SftpDestinationInput()).validate().keys,
        )
        assertEquals(
            setOf("spod.environment", "spod.timeoutSeconds"),
            validSpodInput.copy(spod = SpodDestinationInput()).validate().keys,
        )
    }

    @Test
    fun `identifiers and channel are checked for shape`() {
        assertEquals(
            listOf("SupplierId must be positive"),
            validSftpInput.copy(supplierId = 0).validate().getValue("supplierId"),
        )
        assertEquals(
            listOf("Channel must be one of: SFTP, SPOD"),
            validSftpInput.copy(channel = "FTP").validate().getValue("channel"),
        )
    }

    @Test
    fun `exactly the block of the channel must be present`() {
        assertEquals(
            listOf("SFTP destinations require the sftp block"),
            validSftpInput.copy(sftp = null).validate().getValue("channel"),
        )
        assertEquals(
            listOf("SFTP destinations must not carry the spod block"),
            validSftpInput.copy(spod = validSpodBlock).validate().getValue("channel"),
        )
        assertEquals(
            listOf("SPOD destinations require the spod block"),
            validSpodInput.copy(spod = null).validate().getValue("channel"),
        )
        assertEquals(
            listOf("SPOD destinations must not carry the sftp block"),
            validSpodInput.copy(sftp = validSftpBlock).validate().getValue("channel"),
        )
    }

    @Test
    fun `an unknown channel says nothing about the blocks`() {
        assertEquals(
            listOf("Channel must be one of: SFTP, SPOD"),
            validSftpInput.copy(channel = "FTP", sftp = null).validate().getValue("channel"),
        )
    }

    @Test
    fun `port and timeout must stay within sensible bounds`() {
        assertEquals(emptyMap(), validSftpInput.withSftpPort(1).validate())
        assertEquals(emptyMap(), validSftpInput.withSftpPort(65535).validate())
        assertEquals(
            listOf("Port must be between 1 and 65535"),
            validSftpInput.withSftpPort(0).validate().getValue("sftp.port"),
        )
        assertEquals(
            listOf("Port must be between 1 and 65535"),
            validSftpInput.withSftpPort(65536).validate().getValue("sftp.port"),
        )

        assertEquals(emptyMap(), validSftpInput.withSftpTimeout(1).validate())
        assertEquals(emptyMap(), validSftpInput.withSftpTimeout(3600).validate())
        assertEquals(
            listOf("TimeoutSeconds must be between 1 and 3600"),
            validSftpInput.withSftpTimeout(0).validate().getValue("sftp.timeoutSeconds"),
        )
        assertEquals(
            listOf("TimeoutSeconds must be between 1 and 3600"),
            validSpodInput
                .copy(spod = validSpodBlock.copy(timeoutSeconds = 3601))
                .validate()
                .getValue("spod.timeoutSeconds"),
        )
    }

    @Test
    fun `optional notification email must have a valid shape`() {
        assertEquals(emptyMap(), validSftpInput.copy(notificationEmail = null).validate())
        assertEquals(emptyMap(), validSftpInput.copy(notificationEmail = "  ").validate())
        assertEquals(
            listOf("NotificationEmail must be a valid email address"),
            validSftpInput
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
            validSftpInput.copy(label = overlong).validate().getValue("label"),
        )
        assertEquals(
            listOf("Password must be at most 255 characters"),
            validSftpInput
                .copy(sftp = validSftpBlock.copy(password = overlong))
                .validate()
                .getValue("sftp.password"),
        )
        assertEquals(
            listOf("RemotePath must be at most 1024 characters"),
            validSftpInput
                .copy(sftp = validSftpBlock.copy(remotePath = "/" + "x".repeat(1024)))
                .validate()
                .getValue("sftp.remotePath"),
        )
        assertEquals(
            listOf("AccessToken must be at most 512 characters"),
            validSpodInput
                .copy(spod = validSpodBlock.copy(accessToken = "t".repeat(513)))
                .validate()
                .getValue("spod.accessToken"),
        )
    }

    @Test
    fun `no secret ever appears in the input string representation`() {
        assertFalse(validSftpInput.toString().contains("super-secret"))
        assertTrue(validSftpInput.toString().contains("[redacted]"))
        assertFalse(validSpodInput.toString().contains("spod-access-token"))
        assertTrue(validSpodInput.toString().contains("[redacted]"))
        assertTrue(
            validSftpInput
                .copy(sftp = validSftpBlock.copy(password = null))
                .toString()
                .contains("password=null")
        )
    }

    private fun ProductionDestinationInput.withSftpPort(port: Int): ProductionDestinationInput =
        copy(sftp = validSftpBlock.copy(port = port))

    private fun ProductionDestinationInput.withSftpTimeout(
        timeoutSeconds: Int
    ): ProductionDestinationInput =
        copy(sftp = validSftpBlock.copy(timeoutSeconds = timeoutSeconds))

    private companion object {
        val validSftpBlock =
            SftpDestinationInput(
                host = "sftp.example.test",
                port = 22,
                username = "voenix",
                password = "super-secret",
                hostKeyFingerprint = "SHA256:0123456789abcdef",
                remotePath = "/upload",
                timeoutSeconds = 30,
            )
        val validSpodBlock =
            SpodDestinationInput(
                environment = SpodEnvironment.STAGING,
                accessToken = "spod-access-token",
                timeoutSeconds = 30,
            )
        val validSftpInput =
            ProductionDestinationInput(
                supplierId = 1,
                channel = "SFTP",
                label = "Producer drop",
                enabled = true,
                notificationEmail = "producer@example.test",
                notificationName = "Producer",
                sftp = validSftpBlock,
            )
        val validSpodInput =
            ProductionDestinationInput(
                supplierId = 1,
                channel = "SPOD",
                label = "Spreadconnect",
                enabled = true,
                spod = validSpodBlock,
            )
    }
}

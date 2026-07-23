package shop.voenix.production.delivery.sftp

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import shop.voenix.production.delivery.ProductionDeliveryDestination
import shop.voenix.production.delivery.ProductionDeliveryError
import shop.voenix.production.delivery.ProductionDeliveryResult
import shop.voenix.production.pdf.newTempDirectory

internal class SftpProductionDeliveryTest {
    private val remoteRoot = newTempDirectory()
    private val keyDirectory = newTempDirectory()
    private val adapter = SftpProductionDelivery()
    private val bytes = "%PDF-1.7 production artifact".toByteArray()

    @AfterTest
    fun cleanUp() {
        remoteRoot.toFile().deleteRecursively()
        keyDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `uploads to the exact remote path and final name without leftover temp files`() =
        runBlocking {
            EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
                val inbox = Files.createDirectories(remoteRoot.resolve("inbox"))

                val result =
                    adapter.deliver(
                        destination(server, remotePath = "/inbox"),
                        "ORD-77.pdf",
                        bytes,
                    )

                assertIs<ProductionDeliveryResult.Accepted>(result)
                assertContentEquals(bytes, Files.readAllBytes(inbox.resolve("ORD-77.pdf")))
                assertEquals(
                    listOf(inbox.resolve("ORD-77.pdf")),
                    Files.list(inbox).use { entries -> entries.toList() },
                )
            }
        }

    @Test
    fun `overwrites an earlier remote file and a stale temp file from a crashed attempt`() =
        runBlocking {
            EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
                Files.write(remoteRoot.resolve("ORD-77.pdf"), "old delivery".toByteArray())
                Files.write(remoteRoot.resolve("ORD-77.pdf.part"), "half a file".toByteArray())

                val result =
                    adapter.deliver(destination(server, remotePath = "/"), "ORD-77.pdf", bytes)

                assertIs<ProductionDeliveryResult.Accepted>(result)
                assertContentEquals(bytes, Files.readAllBytes(remoteRoot.resolve("ORD-77.pdf")))
                assertEquals(
                    listOf(remoteRoot.resolve("ORD-77.pdf")),
                    Files.list(remoteRoot).use { entries -> entries.toList() },
                )
            }
        }

    @Test
    fun `a wrong pinned fingerprint rejects the server before credentials are sent`() =
        runBlocking {
            EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
                val wrongFingerprint = "SHA256:" + "A".repeat(43)

                val result =
                    adapter.deliver(
                        destination(server, fingerprint = wrongFingerprint),
                        "ORD-77.pdf",
                        bytes,
                    )

                assertEquals(
                    ProductionDeliveryResult.Failed(ProductionDeliveryError.HOST_KEY_REJECTED),
                    result,
                )
                assertEquals(0, server.authenticationAttempts.get())
                assertRemoteRootEmpty()
            }
        }

    @Test
    fun `a blank pinned fingerprint never falls back to permissive verification`() = runBlocking {
        EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
            val result =
                adapter.deliver(destination(server, fingerprint = "  "), "ORD-77.pdf", bytes)

            assertEquals(
                ProductionDeliveryResult.Failed(ProductionDeliveryError.HOST_KEY_REJECTED),
                result,
            )
            assertEquals(0, server.authenticationAttempts.get())
            assertRemoteRootEmpty()
        }
    }

    @Test
    fun `a fingerprint with a foreign digest algorithm is rejected`() = runBlocking {
        EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
            val md5Style = "MD5:" + server.fingerprint.substringAfter(':')

            val result =
                adapter.deliver(destination(server, fingerprint = md5Style), "ORD-77.pdf", bytes)

            assertEquals(
                ProductionDeliveryResult.Failed(ProductionDeliveryError.HOST_KEY_REJECTED),
                result,
            )
            assertRemoteRootEmpty()
        }
    }

    @Test
    fun `a wrong password fails with a safe code and uploads nothing`() = runBlocking {
        EmbeddedSftpServer(remoteRoot, keyDirectory).use { server ->
            val result =
                adapter.deliver(
                    destination(server).copy(password = "wrong"),
                    "ORD-77.pdf",
                    bytes,
                )

            assertEquals(
                ProductionDeliveryResult.Failed(ProductionDeliveryError.AUTH_FAILED),
                result,
            )
            assertRemoteRootEmpty()
        }
    }

    @Test
    fun `a closed port fails quickly with CONNECTION_FAILED`() = runBlocking {
        val unusedPort = ServerSocket(0).use { socket -> socket.localPort }

        val result =
            withTimeout(30.seconds) {
                adapter.deliver(
                    destination(host = "127.0.0.1", port = unusedPort, fingerprint = "SHA256:x"),
                    "ORD-77.pdf",
                    bytes,
                )
            }

        assertEquals(
            ProductionDeliveryResult.Failed(ProductionDeliveryError.CONNECTION_FAILED),
            result,
        )
    }

    @Test
    fun `a silent server runs into the destination timeout with a bounded code`() = runBlocking {
        // The socket listens but never speaks SSH — TCP connects, then nothing.
        ServerSocket(0).use { silentServer ->
            val result =
                withTimeout(30.seconds) {
                    adapter.deliver(
                        destination(
                            host = "127.0.0.1",
                            port = silentServer.localPort,
                            fingerprint = "SHA256:x",
                            timeoutSeconds = 1,
                        ),
                        "ORD-77.pdf",
                        bytes,
                    )
                }

            val failed = assertIs<ProductionDeliveryResult.Failed>(result)
            assertTrue(
                failed.error in
                    setOf(
                        ProductionDeliveryError.CONNECTION_FAILED,
                        ProductionDeliveryError.AUTH_FAILED,
                    ),
                "expected a connect/auth stage code, got ${failed.error}",
            )
        }
    }

    @Test
    fun `cancellation interrupts the attempt and propagates`() = runBlocking {
        ServerSocket(0).use { silentServer ->
            val delivering = launch {
                adapter.deliver(
                    destination(
                        host = "127.0.0.1",
                        port = silentServer.localPort,
                        fingerprint = "SHA256:x",
                        timeoutSeconds = 3600,
                    ),
                    "ORD-77.pdf",
                    bytes,
                )
            }

            delay(500)
            withTimeout(10.seconds) { delivering.cancelAndJoin() }

            assertTrue(delivering.isCancelled)
        }
    }

    private fun assertRemoteRootEmpty() {
        assertEquals(
            emptyList(),
            Files.list(remoteRoot).use { entries -> entries.toList() },
        )
    }

    private fun destination(
        server: EmbeddedSftpServer,
        remotePath: String = "/",
        fingerprint: String = server.fingerprint,
    ): ProductionDeliveryDestination =
        destination(
            host = "127.0.0.1",
            port = server.port,
            fingerprint = fingerprint,
            remotePath = remotePath,
        )

    private fun destination(
        host: String,
        port: Int,
        fingerprint: String,
        remotePath: String = "/",
        timeoutSeconds: Int = 10,
    ): ProductionDeliveryDestination =
        ProductionDeliveryDestination(
            id = 1,
            channel = "SFTP",
            enabled = true,
            host = host,
            port = port,
            username = SFTP_USERNAME,
            password = SFTP_PASSWORD,
            hostKeyFingerprint = fingerprint,
            remotePath = remotePath,
            timeoutSeconds = timeoutSeconds,
        )
}

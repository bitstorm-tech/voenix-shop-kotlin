package shop.voenix.production.delivery.sftp

import java.security.PublicKey
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.UserAuthFactory
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.delivery.ProductionDeliveryAdapter
import shop.voenix.production.delivery.ProductionDeliveryDestination
import shop.voenix.production.delivery.ProductionDeliveryError
import shop.voenix.production.delivery.ProductionDeliveryResult
import shop.voenix.production.delivery.rethrowCancellationOrError

/**
 * The SFTP delivery adapter (Apache MINA SSHD): password authentication, upload to a temporary
 * `.part` name in the destination's remote path, then rename to the final producer-facing name — a
 * hotfolder consumer never sees a partially written PDF.
 *
 * Host-key verification against the destination's pinned fingerprint is mandatory and happens
 * before authentication; there is no permissive fallback, and a mismatch never sends credentials.
 * The destination's timeout bounds connect, authentication, and idle time. Failures map to the
 * bounded [ProductionDeliveryError] vocabulary by connection stage — no raw exception message,
 * credential, or remote path ever leaves this adapter as data. Cancellation interrupts the blocking
 * transfer and propagates.
 */
internal class SftpProductionDelivery : ProductionDeliveryAdapter {
    override val channel: String = "SFTP"

    override suspend fun deliver(
        destination: ProductionDeliveryDestination,
        fileName: String,
        bytes: ByteArray,
    ): ProductionDeliveryResult {
        val attempt = SftpAttempt(destination)
        val result = runCatching {
            runInterruptible(Dispatchers.IO) { attempt.upload(fileName, bytes) }
        }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            val error = attempt.classify()
            logger.warn(
                "SFTP delivery to destination {} failed with {}",
                destination.id,
                error,
                failure,
            )
            return ProductionDeliveryResult.Failed(error)
        }
        return ProductionDeliveryResult.Accepted
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SftpProductionDelivery::class.java)
    }
}

/** One blocking upload attempt, tracking the stage reached to classify a failure safely. */
private class SftpAttempt(private val destination: ProductionDeliveryDestination) {
    private val stage = AtomicReference(Stage.CONNECT)
    private val hostKeyRejected = AtomicBoolean(false)
    private val timeout: Duration = Duration.ofSeconds(destination.timeoutSeconds.toLong())

    fun upload(fileName: String, bytes: ByteArray) {
        val client = SshClient.setUpDefaultClient()
        client.hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
        client.userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
        client.serverKeyVerifier = ServerKeyVerifier { _, _, serverKey -> verifyHostKey(serverKey) }
        CoreModuleProperties.IDLE_TIMEOUT.set(client, timeout)
        client.start()
        try {
            connect(client).use { session ->
                stage.set(Stage.AUTHENTICATE)
                session.addPasswordIdentity(destination.password)
                session.auth().verify(timeout)
                stage.set(Stage.TRANSFER)
                SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                    transfer(sftp, fileName, bytes)
                }
            }
        } finally {
            client.stop()
        }
    }

    fun classify(): ProductionDeliveryError =
        when {
            hostKeyRejected.get() -> ProductionDeliveryError.HOST_KEY_REJECTED
            stage.get() == Stage.CONNECT -> ProductionDeliveryError.CONNECTION_FAILED
            stage.get() == Stage.AUTHENTICATE -> ProductionDeliveryError.AUTH_FAILED
            else -> ProductionDeliveryError.TRANSFER_FAILED
        }

    private fun connect(client: SshClient): ClientSession =
        client
            .connect(destination.username, destination.host, destination.port)
            .verify(timeout)
            .session

    /**
     * Uploads to `{finalName}.part` (overwriting a stale temp file from an earlier crashed
     * attempt), removes an already existing final file from an earlier at-least-once delivery, and
     * renames — the final name only ever points at a complete file.
     */
    private fun transfer(sftp: SftpClient, fileName: String, bytes: ByteArray) {
        val finalPath = remoteFilePath(fileName)
        val temporaryPath = "$finalPath.part"
        sftp
            .write(
                temporaryPath,
                SftpClient.OpenMode.Write,
                SftpClient.OpenMode.Create,
                SftpClient.OpenMode.Truncate,
            )
            .use { remote -> remote.write(bytes) }
        removeIfExists(sftp, finalPath)
        sftp.rename(temporaryPath, finalPath)
    }

    private fun remoteFilePath(fileName: String): String {
        val directory = destination.remotePath.trim().ifEmpty { "/" }
        return if (directory.endsWith("/")) "$directory$fileName" else "$directory/$fileName"
    }

    private fun removeIfExists(sftp: SftpClient, path: String) {
        try {
            sftp.remove(path)
        } catch (missing: SftpException) {
            if (missing.status != SftpConstants.SSH_FX_NO_SUCH_FILE) throw missing
        }
    }

    /** Rejecting the host key closes the session before any credential is sent. */
    private fun verifyHostKey(serverKey: PublicKey): Boolean {
        val accepted = matchesPinnedFingerprint(serverKey)
        if (!accepted) hostKeyRejected.set(true)
        return accepted
    }

    private fun matchesPinnedFingerprint(serverKey: PublicKey): Boolean {
        val computed = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
        val pinned = destination.hostKeyFingerprint.trim()
        val pinnedDigest =
            when {
                !pinned.contains(':') -> pinned
                pinned.substringBefore(':').equals("SHA256", ignoreCase = true) ->
                    pinned.substringAfter(':')
                else -> return false
            }
        return pinnedDigest.trimEnd('=') == computed.substringAfter(':').trimEnd('=') &&
            pinnedDigest.isNotEmpty()
    }

    private enum class Stage {
        CONNECT,
        AUTHENTICATE,
        TRANSFER,
    }
}

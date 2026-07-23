package shop.voenix.production.delivery.sftp

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory

internal const val SFTP_USERNAME = "producer"
internal const val SFTP_PASSWORD = "secret-word"

/**
 * A local in-process SFTP server (Apache MINA SSHD) for adapter tests: password authentication, a
 * virtual filesystem rooted at [root], and a freshly generated host key whose SHA-256 [fingerprint]
 * tests pin — or deliberately mismatch.
 */
internal class EmbeddedSftpServer(root: Path, keyDirectory: Path) : AutoCloseable {
    /** Counts password checks, proving whether credentials were ever presented. */
    val authenticationAttempts: AtomicInteger = AtomicInteger(0)

    private val hostKeyProvider = SimpleGeneratorHostKeyProvider(keyDirectory.resolve("host.key"))

    private val server: SshServer =
        SshServer.setUpDefaultServer().also { server ->
            server.port = 0
            server.keyPairProvider = hostKeyProvider
            server.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            server.fileSystemFactory = VirtualFileSystemFactory(root)
            server.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                authenticationAttempts.incrementAndGet()
                username == SFTP_USERNAME && password == SFTP_PASSWORD
            }
        }

    val fingerprint: String

    init {
        server.start()
        val hostKey = hostKeyProvider.loadKeys(null).first()
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.public)
    }

    val port: Int
        get() = server.port

    override fun close() {
        server.stop(true)
    }
}

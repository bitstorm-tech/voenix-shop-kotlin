import { createHash } from 'node:crypto'
import { Client, type SFTPWrapper } from 'ssh2'
import { createLogger, errorClass } from '@/platform/logging'

/**
 * An SFTP destination as the delivery stage reads it: the one read model that carries the
 * password, because the upload has to authenticate. Never log it, never answer it.
 */
export interface SftpDestination {
  id: number
  host: string
  port: number
  username: string
  password: string
  hostKeyFingerprint: string
  remotePath: string
  timeoutSeconds: number
}

/** The bounded outcomes of a failed upload, by the stage the attempt reached. */
export type SftpDeliveryError =
  | 'CONNECTION_FAILED'
  | 'HOST_KEY_REJECTED'
  | 'AUTH_FAILED'
  | 'TRANSFER_FAILED'

const logger = createLogger('production-sftp')
const NO_SUCH_FILE = 2

/**
 * Uploads one artifact: password authentication, the pinned host key verified before any
 * credential is sent (no permissive fallback), the bytes written to `{name}.part`, an earlier
 * final file removed, then the rename to the final `ORD-{orderId}.pdf`, so a hotfolder never sees a
 * partial file. The destination's timeout bounds the connection and authentication together, the
 * opening of the SFTP session, and every transfer step on its own. Answers `null` once the server accepted the renamed file, or the
 * bounded code of the stage that failed; nothing about the server, the path, or the credentials
 * leaves this function except the destination id in a log line.
 */
export function deliverOverSftp(
  destination: SftpDestination,
  fileName: string,
  bytes: Uint8Array,
): Promise<SftpDeliveryError | null> {
  const client = new Client()
  const timeoutMillis = destination.timeoutSeconds * 1000
  let stage: 'connect' | 'authenticate' | 'transfer' = 'connect'
  let hostKeyRejected = false
  let settled = false
  let sessionTimer: ReturnType<typeof setTimeout> | undefined

  return new Promise<SftpDeliveryError | null>((resolve) => {
    const finish = (error: unknown) => {
      if (settled) return
      settled = true
      clearTimeout(sessionTimer)
      client.end()
      if (error === null) {
        resolve(null)
        return
      }
      const code: SftpDeliveryError = hostKeyRejected
        ? 'HOST_KEY_REJECTED'
        : stage === 'connect'
          ? 'CONNECTION_FAILED'
          : stage === 'authenticate'
            ? 'AUTH_FAILED'
            : 'TRANSFER_FAILED'
      logger.warn(
        { destinationId: destination.id, code, errorClass: errorClass(error) },
        'SFTP delivery failed',
      )
      resolve(code)
    }

    client
      .on('handshake', () => {
        stage = 'authenticate'
      })
      .on('ready', () => {
        stage = 'transfer'
        // ssh2 drops `readyTimeout` once authenticated, and the step timers start only with the
        // session: a server that never answers the subsystem request needs a deadline of its own.
        sessionTimer = setTimeout(() => {
          finish(new Error('The SFTP session did not open within the destination timeout'))
        }, timeoutMillis)
        client.sftp((error, sftp) => {
          clearTimeout(sessionTimer)
          if (error !== undefined) {
            finish(error)
            return
          }
          transfer(sftp, destination.remotePath, fileName, bytes, timeoutMillis).then(() => {
            finish(null)
          }, finish)
        })
      })
      .on('error', finish)
      .on('close', () => {
        finish(new Error('The SFTP connection closed before the upload finished'))
      })
      .connect({
        host: destination.host,
        port: destination.port,
        username: destination.username,
        password: destination.password,
        authHandler: ['password'],
        readyTimeout: timeoutMillis,
        hostVerifier: (key: Buffer) => {
          const accepted = matchesPinnedFingerprint(key, destination.hostKeyFingerprint)
          if (!accepted) hostKeyRejected = true
          return accepted
        },
      })
  })
}

async function transfer(
  sftp: SFTPWrapper,
  remotePath: string,
  fileName: string,
  bytes: Uint8Array,
  timeoutMillis: number,
) {
  const directory = remotePath.trim() === '' ? '/' : remotePath.trim()
  const finalPath = directory.endsWith('/') ? directory + fileName : `${directory}/${fileName}`
  const temporaryPath = `${finalPath}.part`
  const step = (run: (done: (error?: Error | null) => void) => void, tolerate?: number) =>
    new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error('An SFTP step ran into the destination timeout'))
      }, timeoutMillis)
      run((error) => {
        clearTimeout(timer)
        const code = error !== null && error !== undefined && 'code' in error ? error.code : null
        if (error === null || error === undefined || code === tolerate) resolve()
        else reject(error)
      })
    })
  await step((done) => {
    sftp.writeFile(temporaryPath, Buffer.from(bytes), done)
  })
  await step((done) => {
    sftp.unlink(finalPath, done)
  }, NO_SUCH_FILE)
  await step((done) => {
    sftp.rename(temporaryPath, finalPath, done)
  })
}

/**
 * Whether the server's key hashes to the pinned OpenSSH fingerprint, `SHA256:<base64>` with or
 * without the prefix and its padding. A blank pin or another digest algorithm never matches.
 */
export function matchesPinnedFingerprint(key: Buffer, pinned: string): boolean {
  const value = pinned.trim()
  const separator = value.indexOf(':')
  if (separator >= 0 && value.slice(0, separator).toUpperCase() !== 'SHA256') return false
  const digest = (separator >= 0 ? value.slice(separator + 1) : value).replace(/=+$/, '')
  return digest !== '' && digest === sshFingerprint(key).replace(/=+$/, '')
}

/** The base64 SHA-256 of a public key blob, the part after `SHA256:` that `ssh-keygen -l` prints. */
export const sshFingerprint = (key: Buffer): string =>
  createHash('sha256').update(key).digest('base64')

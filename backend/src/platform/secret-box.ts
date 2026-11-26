import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto'

const PREFIX = 'enc:v1:'
const IV_BYTES = 12
const TAG_BYTES = 16

export type SecretBox = ReturnType<typeof createSecretBox>

/**
 * AES-256-GCM for credentials the application stores and must read back (SFTP passwords, SPOD
 * access tokens): a database dump alone reveals none of them, and a changed value fails to open
 * instead of being used. The key is SHA-256 of `production-secrets:<secret>`; the stored form is
 * `enc:v1:` and base64url of the 12-byte IV, the ciphertext, and the 16-byte tag. The version in
 * the prefix leaves room for a later key rotation.
 */
export function createSecretBox(secret: string) {
  const key = createHash('sha256').update(`production-secrets:${secret}`).digest()
  return {
    seal(plaintext: string): string {
      const iv = randomBytes(IV_BYTES)
      const cipher = createCipheriv('aes-256-gcm', key, iv)
      const ciphertext = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()])
      return PREFIX + Buffer.concat([iv, ciphertext, cipher.getAuthTag()]).toString('base64url')
    },

    /**
     * The plaintext of a value `seal` wrote. Anything else throws, and never with the value in the
     * message: a plaintext value from before encryption, a tampered one, or one sealed with
     * another key. There is no plaintext fallback; an admin enters such a secret again.
     */
    open(sealed: string): string {
      if (!sealed.startsWith(PREFIX)) {
        throw new Error('Stored secret is not encrypted; enter it again in the admin')
      }
      const bytes = Buffer.from(sealed.slice(PREFIX.length), 'base64url')
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, IV_BYTES))
        decipher.setAuthTag(bytes.subarray(bytes.length - TAG_BYTES))
        const ciphertext = bytes.subarray(IV_BYTES, bytes.length - TAG_BYTES)
        return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8')
      } catch {
        throw new Error(
          'Stored secret could not be decrypted: tampered, or PRODUCTION_SECRETS_KEY changed',
        )
      }
    },
  }
}

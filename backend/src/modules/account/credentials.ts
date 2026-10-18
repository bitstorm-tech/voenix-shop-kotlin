import { createHash, pbkdf2, randomBytes, timingSafeEqual } from 'node:crypto'
import { promisify } from 'node:util'

/** The production work factor; tests pass a small count, which the encoding records per hash. */
export const DEFAULT_PBKDF2_ITERATIONS = 600_000

const derive = promisify(pbkdf2)
const VERSION = 'v1'
const SALT_BYTES = 16
const HASH_BYTES = 32

export type PasswordHasher = ReturnType<typeof createPasswordHasher>

/**
 * PBKDF2-HMAC-SHA256 with the versioned encoding `v1$<iterations>$<salt>$<hash>` (both parts
 * Base64 without padding), byte for byte the format of the Kotlin `PasswordHasher`, so stored
 * hashes keep verifying. Verification reads the iteration count from the encoding, so a changed
 * work factor never invalidates a stored hash.
 */
export function createPasswordHasher(iterations: number) {
  return {
    hash: async (password: string): Promise<string> => {
      const salt = randomBytes(SALT_BYTES)
      const hash = await derive(password, salt, iterations, HASH_BYTES, 'sha256')
      return [VERSION, String(iterations), unpadded(salt), unpadded(hash)].join('$')
    },

    /** Whether `password` matches `encoded`; a malformed or unknown encoding never matches. */
    verify: async (password: string, encoded: string): Promise<boolean> => {
      const parsed = parse(encoded)
      if (parsed === null) return false
      const actual = await derive(password, parsed.salt, parsed.iterations, HASH_BYTES, 'sha256')
      return timingSafeEqual(parsed.hash, actual)
    },
  }
}

function parse(encoded: string): { iterations: number; salt: Buffer; hash: Buffer } | null {
  const [version, iterationText, saltText, hashText, ...rest] = encoded.split('$')
  if (version !== VERSION || rest.length > 0) return null
  if (iterationText === undefined || !/^\d{1,9}$/.test(iterationText)) return null
  const iterations = Number(iterationText)
  const salt = decodeBase64(saltText)
  const hash = decodeBase64(hashText)
  if (iterations === 0 || salt === null || hash?.length !== HASH_BYTES) return null
  return { iterations, salt, hash }
}

/** Strict Base64: Node's decoder skips characters it does not know, Java's refuses them. */
function decodeBase64(value: string | undefined): Buffer | null {
  return value !== undefined && /^[A-Za-z0-9+/]*={0,2}$/.test(value)
    ? Buffer.from(value, 'base64')
    : null
}

const unpadded = (bytes: Buffer) => bytes.toString('base64').replace(/=+$/, '')

/**
 * A fresh 256-bit random value in URL-safe Base64: the raw token of a mailed link, and the
 * unguessable placeholder password of a supplier login that has not set one yet.
 */
export const newAccountToken = (): string => randomBytes(32).toString('base64url')

/** The stored form of a token: lower-case hex SHA-256 of its UTF-8 bytes. */
export const tokenHashOf = (token: string): string =>
  createHash('sha256').update(token, 'utf8').digest('hex')

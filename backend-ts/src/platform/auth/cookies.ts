import { randomBytes } from 'node:crypto'

export const SESSION_COOKIE = 'voenix.auth'
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
export const GUEST_COOKIE = 'voenix.guest'
export const SESSION_DURATION_SECONDS = 24 * 60 * 60

/** What the auth cookie carries: a claim, created by the account module at login. */
export interface UserSession {
  userId: number
  roles: string[]
  issuedAtEpochSeconds: number
  expiresAtEpochSeconds: number
}

/** What the CSRF cookie carries: the token and the user it was minted for (`null` for a guest). */
export interface CsrfSession {
  token: string
  userId: number | null
}

export interface SealedValue<T> {
  seal(value: T): Promise<string>
  /** The value of a cookie this codec sealed, or `null` for anything tampered with or foreign. */
  open(sealed: string | undefined): Promise<T | null>
}

/**
 * The three cookies the platform seals with keys derived from the one session secret. Each has its
 * own purpose string, so a value sealed for one cookie never opens as another.
 */
export function authCookies(sessionSecret: string) {
  return {
    session: jsonValue<UserSession>(sessionSecret, 'auth'),
    csrf: jsonValue<CsrfSession>(sessionSecret, 'csrf'),
    guest: sealedValue(sessionSecret, 'guest'),
  }
}

function jsonValue<T>(sessionSecret: string, purpose: string): SealedValue<T> {
  const text = sealedValue(sessionSecret, purpose)
  return {
    seal: (value) => text.seal(JSON.stringify(value)),
    open: async (sealed) => {
      const plaintext = await text.open(sealed)
      return plaintext === null ? null : (JSON.parse(plaintext) as T)
    },
  }
}

/**
 * AES-GCM, which encrypts and authenticates in one step: a caller can neither read nor change a
 * sealed value. The key is SHA-256 of `<purpose>:<secret>`; the sealed form is base64url of the
 * 12-byte IV followed by the ciphertext and its tag.
 */
function sealedValue(sessionSecret: string, purpose: string): SealedValue<string> {
  const key = crypto.subtle
    .digest('SHA-256', new TextEncoder().encode(`${purpose}:${sessionSecret}`))
    .then((digest) =>
      crypto.subtle.importKey('raw', digest, 'AES-GCM', false, ['encrypt', 'decrypt']),
    )
  return {
    async seal(value) {
      const iv = randomBytes(12)
      const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        await key,
        new TextEncoder().encode(value),
      )
      return Buffer.concat([iv, new Uint8Array(ciphertext)]).toString('base64url')
    },
    async open(sealed) {
      if (sealed === undefined) return null
      try {
        const bytes = Buffer.from(sealed, 'base64url')
        const plaintext = await crypto.subtle.decrypt(
          { name: 'AES-GCM', iv: bytes.subarray(0, 12) },
          await key,
          bytes.subarray(12),
        )
        return new TextDecoder().decode(plaintext)
      } catch {
        return null
      }
    },
  }
}

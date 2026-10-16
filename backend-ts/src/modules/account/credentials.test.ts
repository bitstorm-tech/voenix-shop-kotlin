import { describe, expect, test } from 'bun:test'
import { createPasswordHasher, tokenHashOf } from '@/modules/account/credentials'

/**
 * Produced by the JDK's `PBKDF2WithHmacSHA256` exactly as the Kotlin `PasswordHasher.hash` calls it
 * (eclipse-temurin 21, `PBEKeySpec(password, salt, 1000, 256)`, Base64 without padding), for the
 * password `correct horse battery` and the fixed salt bytes `i * 17 + 3` (i = 0..15).
 */
const KOTLIN_HASH = 'v1$1000$AxQlNkdYaXqLnK2+z+DxAg$jIQE06UtHKRdiMLPTPLfsUpDdV4VpJtlvZcGJ9LCSnQ'

const hasher = createPasswordHasher(1_000)

describe('password hasher', () => {
  test('verifies a hash the Kotlin backend stored', async () => {
    expect(await hasher.verify('correct horse battery', KOTLIN_HASH)).toBe(true)
    expect(await hasher.verify('wrong horse battery', KOTLIN_HASH)).toBe(false)
  })

  test('hash and verify round-trip, with a fresh salt per hash', async () => {
    const encoded = await hasher.hash('correct horse battery')
    expect(encoded).toMatch(/^v1\$1000\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$/)
    expect(await hasher.verify('correct horse battery', encoded)).toBe(true)
    expect(await hasher.verify('', encoded)).toBe(false)
    expect(await hasher.hash('correct horse battery')).not.toBe(encoded)
  })

  test('reads the iteration count from the encoding, not from the configuration', async () => {
    expect(await createPasswordHasher(7).verify('correct horse battery', KOTLIN_HASH)).toBe(true)
  })

  test('rejects tampered, unknown, and malformed encodings', async () => {
    const tampered = KOTLIN_HASH.replace('jIQE', 'jIQF')
    for (const encoded of [
      tampered,
      KOTLIN_HASH.replace('v1', 'v2'),
      '',
      'v1',
      'v1$abc$def$ghi',
      'v1$0$AAAA$AAAA',
      'v1$1000$not-base64!$AAAA',
      'v1$1000$AAAA$AAAA',
      `${KOTLIN_HASH}$extra`,
    ]) {
      expect(await hasher.verify('correct horse battery', encoded)).toBe(false)
    }
  })
})

test('a token is stored as the lower-case hex SHA-256 of its text', () => {
  expect(tokenHashOf('abc')).toBe(
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  )
})

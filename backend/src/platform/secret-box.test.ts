import { describe, expect, test } from 'bun:test'
import { createSecretBox } from './secret-box'

const box = createSecretBox('a-test-key-with-at-least-32-bytes-of-text')

describe('secret box', () => {
  test('a sealed value opens to the plaintext and never contains it', () => {
    const sealed = box.seal('hunter2 ünïcode')
    expect(sealed).toStartWith('enc:v1:')
    expect(sealed).not.toContain('hunter2')
    expect(box.open(sealed)).toBe('hunter2 ünïcode')
  })

  test('the same plaintext seals differently every time', () => {
    expect(box.seal('token')).not.toBe(box.seal('token'))
  })

  test('a plaintext value is refused, not passed through', () => {
    expect(() => box.open('hunter2')).toThrow('not encrypted')
  })

  test('a tampered value, a truncated one, and another key fail to open', () => {
    const sealed = box.seal('token')
    const body = Buffer.from(sealed.slice('enc:v1:'.length), 'base64url')
    body[body.length - 20] = (body[body.length - 20] ?? 0) ^ 1
    expect(() => box.open(`enc:v1:${body.toString('base64url')}`)).toThrow('could not be decrypted')
    expect(() => box.open('enc:v1:abc')).toThrow('could not be decrypted')
    const other = createSecretBox('another-key-with-at-least-32-bytes-of-text')
    expect(() => other.open(sealed)).toThrow('could not be decrypted')
  })
})

import { describe, expect, test } from 'bun:test'
import { errorDetails } from '@/platform/logging'

describe('errorDetails', () => {
  test('keeps the class and the stack frames, never the message', () => {
    const details = errorDetails(new TypeError('quotes customer@example.com'))
    expect(details.class).toBe('TypeError')
    expect(details.stack).toContain('logging.test.ts')
    expect(JSON.stringify(details)).not.toContain('customer@example.com')
  })

  test('keeps the codes and database object names, not the values Postgres quotes', () => {
    const error = Object.assign(new Error('duplicate key value violates unique constraint'), {
      code: '23505',
      table: 'users',
      constraint: 'users_email_key',
      detail: 'Key (email)=(customer@example.com) already exists.',
    })
    const details = errorDetails(error)
    expect(details).toMatchObject({ code: '23505', table: 'users', constraint: 'users_email_key' })
    expect(JSON.stringify(details)).not.toContain('customer@example.com')
  })

  test('describes a network failure by its code, without the URL', () => {
    const error = Object.assign(new TypeError('Unable to connect'), {
      code: 'ConnectionRefused',
      path: 'https://api.example.com/orders?token=secret',
    })
    const details = errorDetails(error)
    expect(details.code).toBe('ConnectionRefused')
    expect(JSON.stringify(details)).not.toContain('secret')
  })

  test('follows the cause chain and describes a thrown non-error by its type', () => {
    const error = new Error('outer', {
      cause: Object.assign(new Error('inner'), { code: 'EPIPE' }),
    })
    expect(errorDetails(error).cause).toMatchObject({ class: 'Error', code: 'EPIPE' })
    expect(errorDetails('a string')).toEqual({ class: 'string' })
  })
})

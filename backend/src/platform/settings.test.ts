import { describe, expect, test } from 'bun:test'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  loadEnvFiles,
  loadSettings,
  logStartupConfig,
  productionConfigWarnings,
  startupConfigSummary,
} from '@/platform/settings'

/** The variables `.env.development` sets; everything else falls through to the defaults. */
const development = {
  DATABASE_NAME: 'voenix_kotlin',
  DATABASE_USERNAME: 'voenix',
  DATABASE_PASSWORD: 'voenix',
  AUTH_SESSION_SECRET: 'development-only-session-secret-not-for-production',
  FRONTEND_BASE_URL: 'http://localhost:5173',
  GENERATOR_DUMMY_MODE: 'true',
  MOLLIE_API_KEY: 'test_development-placeholder',
  MOLLIE_WEBHOOK_URL: 'https://localhost/api/payments/webhook/development-webhook-secret',
  MOLLIE_WEBHOOK_SECRET: 'development-webhook-secret',
}

const failure = (env: Record<string, string | undefined>): string => {
  try {
    loadSettings(env)
  } catch (error) {
    return error instanceof Error ? error.message : String(error)
  }
  throw new Error('expected the configuration to be rejected')
}

describe('loadSettings', () => {
  test('the development values parse and the absent variables take the base defaults', () => {
    const settings = loadSettings(development)
    expect(settings.port).toBe(8080)
    expect(settings.database).toEqual({
      host: 'localhost',
      port: 5432,
      name: 'voenix_kotlin',
      username: 'voenix',
      password: 'voenix',
      searchPath: 'voenix',
      sslMode: 'disable',
      maximumPoolSize: 20,
    })
    expect(settings.frontend).toEqual({ baseUrl: 'http://localhost:5173', distPath: null })
    expect(settings.mollie.redirectUrl).toBe('http://localhost:5173/order-confirmation')
    expect(settings.email.enabled).toBe(false)
    expect(settings.email.pollIntervalMinutes).toBe(1)
    expect(settings.email.fromName).toBe('Voenix Shop')
    expect(settings.production.spod).toBeNull()
    expect(settings.generator.dummyMode).toBe(true)
    expect(settings.generator.globalMaxPerHour).toBe(200)
    expect(settings.rateLimit.trustForwardedFor).toBe(false)
    expect(settings.image.cacheRoot).toBe('./data/images/cache')
  })

  test('a missing or blank required variable fails with its name', () => {
    expect(failure({})).toContain('DATABASE_NAME is required')
    expect(failure({})).toContain('AUTH_SESSION_SECRET is required')
    expect(failure({})).toContain('MOLLIE_API_KEY is required')
    expect(failure({ ...development, DATABASE_PASSWORD: '   ' })).toBe(
      'Invalid configuration: DATABASE_PASSWORD is required',
    )
  })

  test('the database password is taken as written, surrounding spaces included', () => {
    const settings = loadSettings({ ...development, DATABASE_PASSWORD: ' secret ' })
    expect(settings.database.password).toBe(' secret ')
    expect(failure({ ...development, DATABASE_PASSWORD: undefined })).toBe(
      'Invalid configuration: DATABASE_PASSWORD is required',
    )
  })

  test('the session secret needs 32 UTF-8 bytes', () => {
    expect(failure({ ...development, AUTH_SESSION_SECRET: 'é'.repeat(15) + 'a' })).toBe(
      'Invalid configuration: AUTH_SESSION_SECRET must contain at least 32 UTF-8 bytes',
    )
    expect(
      loadSettings({ ...development, AUTH_SESSION_SECRET: 'é'.repeat(16) }).auth.sessionSecret,
    ).toBe('é'.repeat(16))
  })

  test('the frontend base URL is normalized and must be HTTPS outside local hosts', () => {
    const baseUrl = (value: string) =>
      loadSettings({ ...development, FRONTEND_BASE_URL: value }).frontend.baseUrl
    expect(baseUrl('https://shop.example.com/')).toBe('https://shop.example.com')
    expect(baseUrl('http://127.0.0.1:5173')).toBe('http://127.0.0.1:5173')
    // The auth cookies are Secure everywhere but on a local HTTP frontend.
    const secure = (value: string) =>
      loadSettings({ ...development, FRONTEND_BASE_URL: value }).auth.secureCookies
    expect(secure('https://shop.example.com')).toBe(true)
    expect(secure('https://localhost:5173')).toBe(true)
    expect(secure('http://localhost:5173')).toBe(false)
    expect(secure('http://127.0.0.1:5173')).toBe(false)
    expect(failure({ ...development, FRONTEND_BASE_URL: 'http://shop.example.com' })).toContain(
      'FRONTEND_BASE_URL must use HTTPS outside local environments',
    )
    expect(failure({ ...development, FRONTEND_BASE_URL: 'not a url' })).toContain(
      'FRONTEND_BASE_URL must be an absolute HTTP(S) URL',
    )
    expect(failure({ ...development, FRONTEND_BASE_URL: 'ftp://shop.example.com' })).toContain(
      'FRONTEND_BASE_URL must be an absolute HTTP(S) URL',
    )
  })

  test('the search path is one lowercase identifier of at most 63 characters', () => {
    expect(
      loadSettings({ ...development, DATABASE_SEARCH_PATH: 'catalog' }).database.searchPath,
    ).toBe('catalog')
    expect(failure({ ...development, DATABASE_SEARCH_PATH: 'a'.repeat(64) })).toContain(
      'DATABASE_SEARCH_PATH',
    )
  })

  test('the rules that span two variables', () => {
    expect(failure({ ...development, EMAIL_ENABLED: 'true' })).toContain(
      'EMAIL_API_KEY is required when email is enabled',
    )
    expect(failure({ ...development, GENERATOR_DUMMY_MODE: 'false' })).toContain(
      'GENERATOR_API_KEY is required unless dummy mode is enabled',
    )
    expect(failure({ ...development, MOLLIE_WEBHOOK_SECRET: 'another-webhook-secret' })).toContain(
      'MOLLIE_WEBHOOK_URL must end in the webhook secret',
    )
    expect(
      failure({
        ...development,
        PRODUCTION_SPOD_WEBHOOK_SECRET: 'too-short',
        OPERATIONS_ALERT_EMAIL: 'ops@example.com',
      }),
    ).toBe('Invalid configuration: PRODUCTION_SPOD_WEBHOOK_SECRET must be at least 32 characters')
    expect(failure({ ...development, PRODUCTION_SPOD_WEBHOOK_SECRET: 'a'.repeat(32) })).toBe(
      'Invalid configuration: OPERATIONS_ALERT_EMAIL is required when PRODUCTION_SPOD_WEBHOOK_SECRET is set',
    )
    const settings = loadSettings({
      ...development,
      PRODUCTION_SPOD_WEBHOOK_SECRET: 'a'.repeat(32),
      OPERATIONS_ALERT_EMAIL: 'ops@example.com',
    })
    expect(settings.production.spod).toEqual({ webhookSecret: 'a'.repeat(32) })
    expect(settings.operations).toEqual({ alertEmail: 'ops@example.com' })
  })

  test('the operations alert address is optional and free of control characters', () => {
    expect(loadSettings(development).operations).toEqual({ alertEmail: null })
    const alone = loadSettings({ ...development, OPERATIONS_ALERT_EMAIL: ' ops@example.com ' })
    expect(alone.operations).toEqual({ alertEmail: 'ops@example.com' })
    expect(alone.production.spod).toBeNull()
    expect(failure({ ...development, OPERATIONS_ALERT_EMAIL: 'ops@example.com\nBcc: x@y.z' })).toBe(
      'Invalid configuration: OPERATIONS_ALERT_EMAIL must not contain control characters',
    )
  })

  test('the generator global hourly limit defaults to 200 and must be at least 1', () => {
    expect(
      loadSettings({ ...development, GENERATOR_GLOBAL_MAX_PER_HOUR: '50' }).generator
        .globalMaxPerHour,
    ).toBe(50)
    expect(failure({ ...development, GENERATOR_GLOBAL_MAX_PER_HOUR: '0' })).toBe(
      'Invalid configuration: GENERATOR_GLOBAL_MAX_PER_HOUR must be at least 1',
    )
  })

  test('the database maximum pool size defaults to 20 and must be at least 1', () => {
    expect(
      loadSettings({ ...development, DATABASE_MAXIMUM_POOL_SIZE: '15' }).database.maximumPoolSize,
    ).toBe(15)
    expect(failure({ ...development, DATABASE_MAXIMUM_POOL_SIZE: '0' })).toBe(
      'Invalid configuration: DATABASE_MAXIMUM_POOL_SIZE must be at least 1',
    )
  })
})

describe('startup config summary and warnings', () => {
  const httpsProduction = {
    ...development,
    FRONTEND_BASE_URL: 'https://voenix.shop',
    DATABASE_SSL_MODE: 'require',
    EMAIL_ENABLED: 'true',
    EMAIL_API_KEY: 'sweego-secret-key',
    EMAIL_FROM_EMAIL: 'shop@voenix.shop',
    GENERATOR_DUMMY_MODE: 'false',
    GENERATOR_API_KEY: 'fal-secret-key',
    RATE_LIMIT_TRUST_FORWARDED_FOR: 'true',
    MOLLIE_API_KEY: 'live_mollie-secret',
  }

  test('builds a secret-free summary and classifies the Mollie key prefix', () => {
    const summary = startupConfigSummary(loadSettings(httpsProduction))
    expect(summary).toEqual({
      frontendBaseUrl: 'https://voenix.shop',
      secureCookies: true,
      emailEnabled: true,
      emailPollIntervalMinutes: 1,
      databaseSslMode: 'require',
      databaseMaxPoolSize: 20,
      rateLimitTrustForwardedFor: true,
      generatorDummyMode: false,
      mollieMode: 'live',
    })
    const serialized = JSON.stringify(summary)
    expect(serialized).not.toContain('sweego-secret-key')
    expect(serialized).not.toContain('fal-secret-key')
    expect(serialized).not.toContain('live_mollie-secret')
    expect(serialized).not.toContain('development-only-session-secret')

    expect(startupConfigSummary(loadSettings(development)).mollieMode).toBe('test')
    expect(
      startupConfigSummary(loadSettings({ ...development, MOLLIE_API_KEY: 'other_key' }))
        .mollieMode,
    ).toBe('unknown')
  })

  test('emits no warnings for local HTTP or for a properly hardened HTTPS deployment', () => {
    expect(productionConfigWarnings(loadSettings(development))).toEqual([])
    expect(productionConfigWarnings(loadSettings(httpsProduction))).toEqual([])
  })

  test('warns on every unsafe default when FRONTEND_BASE_URL uses HTTPS', () => {
    const unsafeHttps = loadSettings({
      ...development,
      FRONTEND_BASE_URL: 'https://voenix.shop',
    })
    const warnings = productionConfigWarnings(unsafeHttps)
    expect(warnings).toHaveLength(4)
    expect(warnings.some((line) => line.includes('DATABASE_SSL_MODE=disable'))).toBe(true)
    expect(warnings.some((line) => line.includes('EMAIL_ENABLED=false'))).toBe(true)
    expect(warnings.some((line) => line.includes('GENERATOR_DUMMY_MODE=true'))).toBe(true)
    expect(warnings.some((line) => line.includes('RATE_LIMIT_TRUST_FORWARDED_FOR=false'))).toBe(
      true,
    )

    const infos: unknown[][] = []
    const warns: unknown[][] = []
    logStartupConfig(unsafeHttps, {
      info: (...args: unknown[]) => {
        infos.push(args)
      },
      warn: (...args: unknown[]) => {
        warns.push(args)
      },
    })
    expect(infos).toHaveLength(1)
    expect(warns).toHaveLength(4)
  })
})

describe('loadEnvFiles', () => {
  test('fills process.env from .env.development without overriding a variable that is already set', () => {
    const previous = process.env.FRONTEND_BASE_URL
    process.env.FRONTEND_BASE_URL = 'http://127.0.0.1:4173'
    try {
      loadEnvFiles()
      expect(process.env.FRONTEND_BASE_URL).toBe('http://127.0.0.1:4173')
      expect(process.env.MOLLIE_WEBHOOK_SECRET).toBeDefined()
      expect(() => loadSettings(process.env)).not.toThrow()
    } finally {
      if (previous === undefined) delete process.env.FRONTEND_BASE_URL
      else process.env.FRONTEND_BASE_URL = previous
    }
  })

  test('lets a later file win over an earlier one and skips a missing file', () => {
    const directory = mkdtempSync(join(tmpdir(), 'env-files-'))
    writeFileSync(join(directory, 'first'), 'LOAD_ENV_FILES_A=first\nLOAD_ENV_FILES_B=first\n')
    writeFileSync(join(directory, 'second'), 'LOAD_ENV_FILES_B="second"\n')
    try {
      loadEnvFiles([
        join(directory, 'first'),
        join(directory, 'missing'),
        join(directory, 'second'),
      ])
      expect(process.env.LOAD_ENV_FILES_A).toBe('first')
      expect(process.env.LOAD_ENV_FILES_B).toBe('second')
    } finally {
      delete process.env.LOAD_ENV_FILES_A
      delete process.env.LOAD_ENV_FILES_B
      rmSync(directory, { recursive: true, force: true })
    }
  })
})

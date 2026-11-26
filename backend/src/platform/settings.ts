import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { z } from 'zod'
import type { Logger } from '@/platform/logging'

/**
 * Every setting the application reads, parsed once from environment variables. The names are the
 * upper-snake-case form of the Kotlin backend's YAML keys; the defaults are the ones of its base
 * `application.yaml`. A missing required variable fails with a message naming it, before anything
 * touches the database. See docs/dev/backend/getting-started/running-the-development-server.md.
 */
export type Settings = ReturnType<typeof loadSettings>

const required = z.string({ error: 'is required' }).trim().min(1, 'is required')
/** Like `required`, but the value is taken as written: a password may begin or end with a space. */
const requiredUntrimmed = z
  .string({ error: 'is required' })
  .refine((value) => value.trim() !== '', 'is required')
const optional = z.string().trim().default('')
const booleanText = z.enum(['true', 'false']).transform((value) => value === 'true')
const integerText = z.string().regex(/^\d+$/, 'must be a whole number').transform(Number)
const noControlCharacters = (value: string) => !/[\p{Cc}]/u.test(value)

const schema = z
  .object({
    PORT: integerText.default(8080),

    DATABASE_HOST: z.string().trim().min(1).default('localhost'),
    DATABASE_PORT: integerText.default(5432),
    DATABASE_NAME: required,
    DATABASE_USERNAME: required,
    DATABASE_PASSWORD: requiredUntrimmed,
    DATABASE_SEARCH_PATH: z
      .string()
      .regex(/^[a-z_][a-z0-9_]{0,62}$/, 'must be one lowercase PostgreSQL identifier')
      .default('voenix'),
    DATABASE_SSL_MODE: z
      .enum(['disable', 'require', 'verify-ca', 'verify-full'])
      .default('disable'),
    DATABASE_MAXIMUM_POOL_SIZE: integerText
      .refine((value) => value >= 1, 'must be at least 1')
      .default(20),

    AUTH_SESSION_SECRET: required.refine(
      (value) => Buffer.byteLength(value, 'utf8') >= 32,
      'must contain at least 32 UTF-8 bytes',
    ),

    FRONTEND_BASE_URL: z.string().default('http://localhost:5173').transform(frontendBaseUrl),
    FRONTEND_DIST_PATH: optional,

    EMAIL_ENABLED: booleanText.default(false),
    EMAIL_POLL_INTERVAL_MINUTES: integerText
      .refine((value) => value >= 1 && value <= 1440, 'must be between 1 and 1440')
      .default(1),
    EMAIL_API_KEY: optional,
    EMAIL_FROM_EMAIL: optional,
    EMAIL_FROM_NAME: z
      .string()
      .trim()
      .min(1, 'must contain between 1 and 255 characters')
      .max(255, 'must contain between 1 and 255 characters')
      .refine(noControlCharacters, 'must not contain control characters')
      .default('Voenix Shop'),

    PRODUCTION_ARTIFACT_ROOT: required.default('./data/production/artifacts'),
    PRODUCTION_SPOD_WEBHOOK_SECRET: optional,
    // The partner's callbacks need a public HTTPS address; locally an ngrok tunnel to the backend.
    PRODUCTION_SPOD_WEBHOOK_BASE_URL: optional
      .transform((value) => value.replace(/\/+$/, ''))
      .refine((value) => value === '' || value.startsWith('https://'), 'must be an HTTPS URL'),
    PRODUCTION_SECRETS_KEY: required.refine(
      (value) => Buffer.byteLength(value, 'utf8') >= 32,
      'must contain at least 32 UTF-8 bytes',
    ),

    OPERATIONS_ALERT_EMAIL: optional,

    GENERATOR_DUMMY_MODE: booleanText.default(false),
    GENERATOR_API_KEY: optional,
    GENERATOR_GLOBAL_MAX_PER_HOUR: integerText
      .refine((value) => value >= 1, 'must be at least 1')
      .default(200),

    MOLLIE_API_KEY: required,
    MOLLIE_WEBHOOK_URL: required.refine(
      (value) => value.startsWith('https://'),
      'must be an absolute HTTPS URL',
    ),
    MOLLIE_WEBHOOK_SECRET: required.refine(
      (value) => value.length >= 16,
      'must be at least 16 characters',
    ),

    RATE_LIMIT_TRUST_FORWARDED_FOR: booleanText.default(false),

    IMAGE_PUBLIC_ROOT: required.default('./data/images/public'),
    IMAGE_PRIVATE_ROOT: required.default('./data/images/private'),
    IMAGE_CACHE_ROOT: required.default('./data/images/cache'),
  })
  .superRefine((env, context) => {
    const issue = (path: keyof typeof env, message: string) => {
      context.addIssue({ code: 'custom', path: [path], message })
    }
    if (env.EMAIL_ENABLED && env.EMAIL_API_KEY === '') {
      issue('EMAIL_API_KEY', 'is required when email is enabled')
    }
    if (env.EMAIL_ENABLED && env.EMAIL_FROM_EMAIL === '') {
      issue('EMAIL_FROM_EMAIL', 'is required when email is enabled')
    }
    if (!env.GENERATOR_DUMMY_MODE && env.GENERATOR_API_KEY === '') {
      issue('GENERATOR_API_KEY', 'is required unless dummy mode is enabled')
    }
    // The print-on-demand channel mails its operator decisions to the operations address, so a
    // SPOD channel without one would quarantine and cancel jobs without telling anyone.
    const spodConfigured = env.PRODUCTION_SPOD_WEBHOOK_SECRET !== ''
    if (spodConfigured && env.PRODUCTION_SPOD_WEBHOOK_SECRET.length < 32) {
      issue('PRODUCTION_SPOD_WEBHOOK_SECRET', 'must be at least 32 characters')
    }
    if (spodConfigured && env.OPERATIONS_ALERT_EMAIL === '') {
      issue('OPERATIONS_ALERT_EMAIL', 'is required when PRODUCTION_SPOD_WEBHOOK_SECRET is set')
    }
    if (env.PRODUCTION_SECRETS_KEY === env.AUTH_SESSION_SECRET) {
      issue('PRODUCTION_SECRETS_KEY', 'must differ from AUTH_SESSION_SECRET')
    }
    if (!noControlCharacters(env.OPERATIONS_ALERT_EMAIL)) {
      issue('OPERATIONS_ALERT_EMAIL', 'must not contain control characters')
    }
    // The webhook route only answers on /api/payments/webhook/<secret>, so a URL ending elsewhere
    // describes a deployment in which every real callback is refused.
    if (lastPathSegment(env.MOLLIE_WEBHOOK_URL) !== env.MOLLIE_WEBHOOK_SECRET) {
      issue('MOLLIE_WEBHOOK_URL', 'must end in the webhook secret')
    }
  })

/**
 * Parses `env` (normally `process.env`) into the settings object, grouped by the module that reads
 * each block. Pure: it reads no file, so a test passes a plain record.
 */
export function loadSettings(env: Record<string, string | undefined>) {
  const parsed = schema.safeParse(env)
  if (!parsed.success) {
    const problems = parsed.error.issues.map((issue) => `${String(issue.path[0])} ${issue.message}`)
    throw new Error(`Invalid configuration: ${problems.join('; ')}`)
  }
  const v = parsed.data
  return {
    port: v.PORT,
    database: {
      host: v.DATABASE_HOST,
      port: v.DATABASE_PORT,
      name: v.DATABASE_NAME,
      username: v.DATABASE_USERNAME,
      password: v.DATABASE_PASSWORD,
      searchPath: v.DATABASE_SEARCH_PATH,
      sslMode: v.DATABASE_SSL_MODE,
      maximumPoolSize: v.DATABASE_MAXIMUM_POOL_SIZE,
    },
    auth: {
      sessionSecret: v.AUTH_SESSION_SECRET,
      // Render ends TLS at its proxy, so the request scheme says nothing. The frontend URL is HTTPS
      // everywhere but on a local host (`frontendBaseUrlProblem`), so only local development is
      // left without `Secure`, and no request header can switch it off.
      secureCookies: v.FRONTEND_BASE_URL.startsWith('https:'),
    },
    frontend: {
      baseUrl: v.FRONTEND_BASE_URL,
      /** The built frontend to serve, or `null` when the Vite dev server serves it. */
      distPath: v.FRONTEND_DIST_PATH === '' ? null : v.FRONTEND_DIST_PATH,
    },
    email: {
      enabled: v.EMAIL_ENABLED,
      pollIntervalMinutes: v.EMAIL_POLL_INTERVAL_MINUTES,
      apiKey: v.EMAIL_API_KEY,
      fromEmail: v.EMAIL_FROM_EMAIL,
      fromName: v.EMAIL_FROM_NAME,
    },
    production: {
      artifactRoot: v.PRODUCTION_ARTIFACT_ROOT,
      /** Encrypts the SFTP passwords and SPOD access tokens stored in the destination tables. */
      secretsKey: v.PRODUCTION_SECRETS_KEY,
      /** The print-on-demand channel, or `null` in a deployment without that supplier. */
      spod:
        v.PRODUCTION_SPOD_WEBHOOK_SECRET === ''
          ? null
          : {
              webhookSecret: v.PRODUCTION_SPOD_WEBHOOK_SECRET,
              /** Where the partner reaches this deployment: the override, else the frontend URL. */
              webhookBaseUrl: v.PRODUCTION_SPOD_WEBHOOK_BASE_URL || v.FRONTEND_BASE_URL,
            },
    },
    operations: {
      /** Where the operations alert and the SPOD ops alert go, or `null` to only log them. */
      alertEmail: v.OPERATIONS_ALERT_EMAIL === '' ? null : v.OPERATIONS_ALERT_EMAIL,
    },
    generator: {
      dummyMode: v.GENERATOR_DUMMY_MODE,
      apiKey: v.GENERATOR_API_KEY,
      globalMaxPerHour: v.GENERATOR_GLOBAL_MAX_PER_HOUR,
    },
    mollie: {
      apiKey: v.MOLLIE_API_KEY,
      /** Where Mollie sends the customer back: the confirmation page of the order they paid for. */
      redirectUrl: `${v.FRONTEND_BASE_URL}/order-confirmation`,
      webhookUrl: v.MOLLIE_WEBHOOK_URL,
      webhookSecret: v.MOLLIE_WEBHOOK_SECRET,
    },
    rateLimit: { trustForwardedFor: v.RATE_LIMIT_TRUST_FORWARDED_FOR },
    image: {
      publicRoot: v.IMAGE_PUBLIC_ROOT,
      privateRoot: v.IMAGE_PRIVATE_ROOT,
      cacheRoot: v.IMAGE_CACHE_ROOT,
    },
  }
}

/**
 * Reads `.env.development`, then `.env.local` from the project root, then the secret file
 * `/etc/secrets/.env` that Render mounts into a container, into `process.env`. A missing file is
 * skipped. A later file wins over an earlier one, and a variable that is already set in the real
 * environment wins over all of them, so a container configured through its environment is never
 * overridden by a file. Call it once, before `loadSettings(process.env)`.
 */
export function loadEnvFiles(files: readonly string[] = defaultEnvFiles()): void {
  const fromFiles: Record<string, string> = {}
  for (const file of files) {
    let content: string
    try {
      content = readFileSync(file, 'utf8')
    } catch {
      continue
    }
    for (const line of content.split('\n')) {
      const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line)
      if (match?.[1] !== undefined && match[2] !== undefined) {
        fromFiles[match[1]] = match[2].replace(/^(["'])(.*)\1$/, '$2')
      }
    }
  }
  for (const [name, value] of Object.entries(fromFiles)) {
    process.env[name] ??= value
  }
}

function defaultEnvFiles(): string[] {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  return [`${root}.env.development`, `${root}.env.local`, '/etc/secrets/.env']
}

/**
 * The frontend address every mailed link is built from: trimmed, without a trailing slash, an
 * absolute HTTP(S) URL with a host, and HTTPS everywhere except the local hosts a development
 * machine uses. A mailed link is clicked outside the shop, and plain HTTP would put a token in the
 * open.
 */
function frontendBaseUrl(raw: string, context: z.RefinementCtx): string {
  const value = raw.trim().replace(/\/+$/, '')
  const problem = frontendBaseUrlProblem(value)
  if (problem !== null) {
    context.addIssue({ code: 'custom', message: problem })
    return z.NEVER
  }
  return value
}

function frontendBaseUrlProblem(value: string): string | null {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return 'must be an absolute HTTP(S) URL'
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:')
    return 'must be an absolute HTTP(S) URL'
  if (url.hostname === '') return 'must contain a host'
  if (url.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
    return 'must use HTTPS outside local environments'
  }
  return null
}

/** Whether the configured Mollie key targets live or test payments, inferred from its prefix. */
export type MollieMode = 'live' | 'test' | 'unknown'

export interface StartupConfigSummary {
  frontendBaseUrl: string
  secureCookies: boolean
  emailEnabled: boolean
  emailPollIntervalMinutes: number
  databaseSslMode: Settings['database']['sslMode']
  databaseMaxPoolSize: number
  rateLimitTrustForwardedFor: boolean
  generatorDummyMode: boolean
  mollieMode: MollieMode
}

export function mollieModeOf(apiKey: string): MollieMode {
  if (apiKey.startsWith('live_')) return 'live'
  if (apiKey.startsWith('test_')) return 'test'
  return 'unknown'
}

/** Secret-free snapshot of the operational settings logged once at process startup. */
export function startupConfigSummary(settings: Settings): StartupConfigSummary {
  return {
    frontendBaseUrl: settings.frontend.baseUrl,
    secureCookies: settings.auth.secureCookies,
    emailEnabled: settings.email.enabled,
    emailPollIntervalMinutes: settings.email.pollIntervalMinutes,
    databaseSslMode: settings.database.sslMode,
    databaseMaxPoolSize: settings.database.maximumPoolSize,
    rateLimitTrustForwardedFor: settings.rateLimit.trustForwardedFor,
    generatorDummyMode: settings.generator.dummyMode,
    mollieMode: mollieModeOf(settings.mollie.apiKey),
  }
}

/**
 * Warnings for settings that are fine in local development but dangerous when `FRONTEND_BASE_URL`
 * uses HTTPS (a deployed environment).
 */
export function productionConfigWarnings(settings: Settings): string[] {
  if (!settings.frontend.baseUrl.startsWith('https://')) return []
  const warnings: string[] = []
  if (settings.database.sslMode === 'disable') {
    warnings.push(
      'DATABASE_SSL_MODE=disable while FRONTEND_BASE_URL uses HTTPS; set require or verify-full for a managed database',
    )
  }
  if (!settings.email.enabled) {
    warnings.push(
      'EMAIL_ENABLED=false while FRONTEND_BASE_URL uses HTTPS; no transactional emails will be sent',
    )
  }
  if (settings.generator.dummyMode) {
    warnings.push(
      'GENERATOR_DUMMY_MODE=true while FRONTEND_BASE_URL uses HTTPS; image generator returns uploaded photos unchanged',
    )
  }
  if (!settings.rateLimit.trustForwardedFor) {
    warnings.push(
      'RATE_LIMIT_TRUST_FORWARDED_FOR=false while FRONTEND_BASE_URL uses HTTPS; behind a reverse proxy all clients share one rate-limit bucket',
    )
  }
  return warnings
}

/** Logs the secret-free startup configuration summary and any HTTPS deployment warnings. */
export function logStartupConfig(settings: Settings, log: Pick<Logger, 'info' | 'warn'>): void {
  log.info(startupConfigSummary(settings), 'Startup configuration')
  for (const warning of productionConfigWarnings(settings)) {
    log.warn(warning)
  }
}

function lastPathSegment(url: string): string | null {
  try {
    return decodeURIComponent(new URL(url).pathname.split('/').at(-1) ?? '')
  } catch {
    return null
  }
}

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { z } from 'zod'

/**
 * Every setting the application reads, parsed once from environment variables. The names are the
 * upper-snake-case form of the Kotlin backend's YAML keys; the defaults are the ones of its base
 * `application.yaml`. A missing required variable fails with a message naming it, before anything
 * touches the database. See docs/dev/backend-ts/getting-started/running-the-development-server.md.
 */
export type Settings = ReturnType<typeof loadSettings>

const required = z.string({ error: 'is required' }).trim().min(1, 'is required')
const optional = z.string().trim().default('')
const booleanText = z.enum(['true', 'false']).transform((value) => value === 'true')
const integerText = z.string().regex(/^\d+$/, 'must be a whole number').transform(Number)
const absoluteHttpUrl = (value: string) => /^https?:\/\//.test(value)
const noControlCharacters = (value: string) => !/[\p{Cc}]/u.test(value)

const schema = z
  .object({
    PORT: integerText.default(8080),

    DATABASE_HOST: z.string().trim().min(1).default('localhost'),
    DATABASE_PORT: integerText.default(5432),
    DATABASE_NAME: required,
    DATABASE_USERNAME: required,
    DATABASE_PASSWORD: required,
    DATABASE_SEARCH_PATH: z
      .string()
      .regex(/^[a-z_][a-z0-9_]{0,62}$/, 'must be one lowercase PostgreSQL identifier')
      .default('voenix'),
    DATABASE_SSL_MODE: z
      .enum(['disable', 'require', 'verify-ca', 'verify-full'])
      .default('disable'),
    DATABASE_MAXIMUM_POOL_SIZE: integerText.default(100),

    AUTH_SESSION_SECRET: required.refine(
      (value) => Buffer.byteLength(value, 'utf8') >= 32,
      'must contain at least 32 UTF-8 bytes',
    ),

    FRONTEND_BASE_URL: z.string().default('http://localhost:5173').transform(frontendBaseUrl),
    FRONTEND_DIST_PATH: optional,

    EMAIL_ENABLED: booleanText.default(false),
    EMAIL_POLL_INTERVAL_MINUTES: integerText
      .refine((value) => value >= 1 && value <= 1440, 'must be between 1 and 1440')
      .default(5),
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
    PRODUCTION_SPOD_ALERT_EMAIL: optional,

    GENERATOR_DUMMY_MODE: booleanText.default(false),
    GENERATOR_API_KEY: optional,

    MOLLIE_API_KEY: required,
    MOLLIE_REDIRECT_URL: required.refine(absoluteHttpUrl, 'must be an absolute HTTP(S) URL'),
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
    // The print-on-demand channel is configured as a whole or not at all: a half-filled pair is a
    // mistake that would start a shop whose SPOD orders can never report a shipment.
    const spodConfigured =
      env.PRODUCTION_SPOD_WEBHOOK_SECRET !== '' || env.PRODUCTION_SPOD_ALERT_EMAIL !== ''
    if (spodConfigured && env.PRODUCTION_SPOD_WEBHOOK_SECRET.length < 32) {
      issue('PRODUCTION_SPOD_WEBHOOK_SECRET', 'must be at least 32 characters')
    }
    if (spodConfigured && env.PRODUCTION_SPOD_ALERT_EMAIL === '') {
      issue('PRODUCTION_SPOD_ALERT_EMAIL', 'is required when the SPOD channel is configured')
    }
    if (!noControlCharacters(env.PRODUCTION_SPOD_ALERT_EMAIL)) {
      issue('PRODUCTION_SPOD_ALERT_EMAIL', 'must not contain control characters')
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
    auth: { sessionSecret: v.AUTH_SESSION_SECRET },
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
      /** The print-on-demand channel, or `null` in a deployment without that supplier. */
      spod:
        v.PRODUCTION_SPOD_WEBHOOK_SECRET === ''
          ? null
          : {
              webhookSecret: v.PRODUCTION_SPOD_WEBHOOK_SECRET,
              alertEmail: v.PRODUCTION_SPOD_ALERT_EMAIL,
            },
    },
    generator: { dummyMode: v.GENERATOR_DUMMY_MODE, apiKey: v.GENERATOR_API_KEY },
    mollie: {
      apiKey: v.MOLLIE_API_KEY,
      redirectUrl: v.MOLLIE_REDIRECT_URL,
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
 * Reads `.env.development` and then `.env.local` from the project root into `process.env`. A
 * later file wins over an earlier one, and a variable that is already set in the real environment
 * wins over both, so a container configured through its environment is never overridden by a
 * checked-in file. Call it once, before `loadSettings(process.env)`.
 */
export function loadEnvFiles(): void {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  const fromFiles: Record<string, string> = {}
  for (const file of ['.env.development', '.env.local']) {
    let content: string
    try {
      content = readFileSync(`${root}${file}`, 'utf8')
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

function lastPathSegment(url: string): string | null {
  try {
    return decodeURIComponent(new URL(url).pathname.split('/').at(-1) ?? '')
  } catch {
    return null
  }
}

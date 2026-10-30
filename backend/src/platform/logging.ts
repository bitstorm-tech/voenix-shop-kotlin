import { AsyncLocalStorage } from 'node:async_hooks'
import { dirname, relative } from 'node:path'
import pino, { type Logger } from 'pino'
import pretty from 'pino-pretty'

export type { Logger }

const sourceRoot = dirname(import.meta.dir)
const thisFile = import.meta.path

/**
 * The `file:line` below `src/` of the log statement, read from a stack trace: the first frame in a
 * source file other than this one (the frames above it are pino's own and this mixin).
 */
function caller(): string | undefined {
  for (const frame of new Error().stack?.split('\n') ?? []) {
    const location = /\(?(\/[^()\s]+\.ts):(\d+)(?::\d+)?\)?$/.exec(frame)
    const [, file, line] = location ?? []
    if (file === undefined || file === thisFile || !file.startsWith(`${sourceRoot}/`)) continue
    return `${relative(sourceRoot, file)}:${line ?? ''}`
  }
  return undefined
}

/** The id of the API request being served, if any; set by the request log middleware. */
const requestIds = new AsyncLocalStorage<string>()

/** Runs `serve` with every line it logs, in any module, tagged with `requestId` (the `reqId` field). */
export const withRequestId = <T>(requestId: string, serve: () => T): T =>
  requestIds.run(requestId, serve)

/**
 * Human-readable lines (`[time] LEVEL (name): file:line [reqId] message`) instead of pino's JSON.
 * pino-pretty runs as a plain stream in this process, not as a pino transport in a worker thread.
 * `bun test` sets `NODE_ENV=test`; it logs from `warn` up, so the request lines stay out of it.
 */
const root = pino(
  {
    level: process.env.NODE_ENV === 'test' ? 'warn' : 'info',
    mixin: () => ({ caller: caller(), reqId: requestIds.getStore() }),
  },
  pretty({
    translateTime: 'SYS:HH:MM:ss.l',
    ignore: 'pid,hostname,caller,reqId',
    messageFormat: '{if caller}{caller} {end}{if reqId}[{reqId}] {end}{msg}',
    sync: true,
  }),
)

/**
 * A logger for one module or component; `name` and the file and line of the log statement appear
 * in every line. Log a caught error as `{ error: errorDetails(error) }`, never the error itself:
 * its message can quote what it failed on (a client body, a provider's answer, a stored mail, a
 * value in a database row).
 */
export function createLogger(name: string): Logger {
  return root.child({ name })
}

/** The class of a caught value, the one thing about an error that is always safe to log. */
export const errorClass = (error: unknown): string =>
  error instanceof Error ? error.name : typeof error

/**
 * Fields of a caught error that name *what* failed without quoting any input: the error code of
 * the system call, the network client, or Postgres, and the database object Postgres names.
 * Postgres' `detail` and a fetch error's `path` are left out on purpose, they carry values.
 */
const SAFE_FIELDS = ['code', 'syscall', 'table', 'column', 'constraint'] as const

/** The deepest `cause` chain followed; a cause is an error of its own and described the same way. */
const MAX_CAUSE_DEPTH = 3

export interface ErrorDetails {
  class: string
  code?: string
  syscall?: string
  table?: string
  column?: string
  constraint?: string
  /** The `at …` frames of the stack trace, without its first line, which repeats the message. */
  stack?: string
  cause?: ErrorDetails
}

/**
 * What can be logged about a caught value without its message: its class, the safe fields, the
 * stack frames, and the same for its `cause`. Enough to tell a refused connection from a missing
 * table from a bug at a given line, while the message stays out of the log.
 */
export function errorDetails(error: unknown, depth = 0): ErrorDetails {
  const details: ErrorDetails = { class: errorClass(error) }
  if (typeof error !== 'object' || error === null) return details
  const fields = error as Record<string, unknown>
  for (const field of SAFE_FIELDS) {
    const value = fields[field]
    if (typeof value === 'string' && value !== '') details[field] = value
  }
  const frames = (typeof fields.stack === 'string' ? fields.stack : '')
    .split('\n')
    .filter((line) => /^\s+at /.test(line))
  if (frames.length > 0) details.stack = frames.map((line) => line.trim()).join('\n')
  if (fields.cause !== undefined && depth < MAX_CAUSE_DEPTH) {
    details.cause = errorDetails(fields.cause, depth + 1)
  }
  return details
}

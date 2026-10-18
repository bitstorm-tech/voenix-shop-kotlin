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

/**
 * Human-readable lines (`[time] LEVEL (name): file:line message`) instead of pino's JSON.
 * pino-pretty runs as a plain stream in this process, not as a pino transport in a worker thread.
 */
const root = pino(
  { mixin: () => ({ caller: caller() }) },
  pretty({
    translateTime: 'SYS:HH:MM:ss.l',
    ignore: 'pid,hostname,caller',
    messageFormat: '{if caller}{caller} {end}{msg}',
    sync: true,
  }),
)

/**
 * A logger for one module or component; `name` and the file and line of the log statement appear
 * in every line. Never log the message of a parse error of an untrusted payload (a client body, a
 * provider's answer): it quotes the input it failed on. Log the error class and your own context
 * instead.
 */
export function createLogger(name: string): Logger {
  return root.child({ name })
}

/** The class of a caught value, the one thing about an error that is always safe to log. */
export const errorClass = (error: unknown): string =>
  error instanceof Error ? error.name : typeof error

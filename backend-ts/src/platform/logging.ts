import pino, { type Logger } from 'pino'

export type { Logger }

const root = pino()

/**
 * A logger for one module or component; `name` appears in every line. Never log the message of a
 * parse error of an untrusted payload (a client body, a provider's answer): it quotes the input it
 * failed on. Log the error class and your own context instead.
 */
export function createLogger(name: string): Logger {
  return root.child({ name })
}

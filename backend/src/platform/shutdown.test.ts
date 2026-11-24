import { describe, expect, test } from 'bun:test'
import { createShutdownHandler } from '@/platform/shutdown'

function recordingLog() {
  const calls: { level: 'info' | 'warn' | 'error'; args: unknown[] }[] = []
  return {
    calls,
    log: {
      info: (...args: unknown[]) => {
        calls.push({ level: 'info', args })
      },
      warn: (...args: unknown[]) => {
        calls.push({ level: 'warn', args })
      },
      error: (...args: unknown[]) => {
        calls.push({ level: 'error', args })
      },
    },
  }
}

describe('createShutdownHandler', () => {
  test('stops the server gracefully, then stops the application, then exits 0', async () => {
    const events: string[] = []
    const { log } = recordingLog()
    const shutdown = createShutdownHandler({
      stopServer: (force) => {
        events.push(`server:${String(force)}`)
        return Promise.resolve()
      },
      stopApplication: () => {
        events.push('app')
        return Promise.resolve()
      },
      exit: (code) => {
        events.push(`exit:${String(code)}`)
      },
      log,
    })

    await shutdown('SIGTERM')
    expect(events).toEqual(['server:false', 'app', 'exit:0'])
  })

  test('ignores a second signal while shutdown is already in progress', async () => {
    const events: string[] = []
    let releaseServer!: () => void
    const serverGate = new Promise<void>((resolve) => {
      releaseServer = resolve
    })
    const { log } = recordingLog()
    const shutdown = createShutdownHandler({
      stopServer: async (force) => {
        events.push(`server:${String(force)}`)
        await serverGate
      },
      stopApplication: () => {
        events.push('app')
        return Promise.resolve()
      },
      exit: (code) => {
        events.push(`exit:${String(code)}`)
      },
      log,
    })

    const first = shutdown('SIGTERM')
    const second = shutdown('SIGINT')
    expect(second).toBe(first)
    releaseServer()
    await first
    expect(events).toEqual(['server:false', 'app', 'exit:0'])
  })

  test('forces the server closed after timeout and still drains the application', async () => {
    const events: string[] = []
    const { log, calls } = recordingLog()
    const shutdown = createShutdownHandler({
      stopServer: (force) => {
        events.push(`server:${String(force)}`)
        if (force === true) return Promise.resolve()
        return new Promise<void>(() => {
          // Simulates an open keep-alive connection that never finishes on its own.
        })
      },
      stopApplication: () => {
        events.push('app')
        return Promise.resolve()
      },
      exit: (code) => {
        events.push(`exit:${String(code)}`)
      },
      log,
      serverStopTimeoutMs: 25,
    })

    await shutdown('SIGTERM')
    expect(events).toEqual(['server:false', 'server:true', 'app', 'exit:0'])
    expect(calls.some((entry) => entry.level === 'warn')).toBe(true)
  })

  test('exits with code 1 when application shutdown throws', async () => {
    const exits: number[] = []
    const { log, calls } = recordingLog()
    const shutdown = createShutdownHandler({
      stopServer: () => Promise.resolve(),
      stopApplication: () => Promise.reject(new Error('pool close failed')),
      exit: (code) => {
        exits.push(code)
      },
      log,
    })

    await shutdown('SIGTERM')
    expect(exits).toEqual([1])
    expect(calls.some((entry) => entry.level === 'error')).toBe(true)
  })
})

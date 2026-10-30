import { createApplication } from '@/composition'
import { createLogger, errorDetails } from '@/platform/logging'
import { loadEnvFiles, loadSettings } from '@/platform/settings'

/**
 * The process: settings, the composed application, the HTTP server, and the two workers, in one
 * process. SIGTERM and SIGINT stop the server first, so no new request arrives, then the workers
 * and the pool.
 */
const log = createLogger('main')

loadEnvFiles()
const settings = loadSettings(process.env)
const application = await createApplication(settings)
application.start()
const server = Bun.serve({
  port: settings.port,
  fetch: application.app.fetch,
  // Bun closes a connection that stays silent for 10 s by default, but an image generation waits
  // up to 2 × 120 s for fal.ai and a SPOD catalog sync is paced at about one request per second.
  // 255 s is Bun's maximum; a sync that runs longer finishes server-side without its client.
  idleTimeout: 255,
})
log.info({ port: server.port }, 'Voenix backend listening')

// Only after the start: a failed start prints its whole message (which setting is missing) to
// stderr. From here on a crash goes through the logger like every other error, then exits as the
// runtime would have.
process.on('uncaughtException', (error) => {
  log.fatal({ error: errorDetails(error) }, 'Uncaught exception')
  process.exit(1)
})
process.on('unhandledRejection', (reason) => {
  log.fatal({ error: errorDetails(reason) }, 'Unhandled promise rejection')
  process.exit(1)
})

const shutdown = (signal: string) => {
  log.info({ signal }, 'Shutting down')
  void (async () => {
    await server.stop()
    await application.stop()
    process.exit(0)
  })()
}
process.on('SIGTERM', () => {
  shutdown('SIGTERM')
})
process.on('SIGINT', () => {
  shutdown('SIGINT')
})

import { createApplication } from '@/composition'
import { createLogger, errorDetails } from '@/platform/logging'
import { loadEnvFiles, loadSettings, logStartupConfig } from '@/platform/settings'
import { createShutdownHandler } from '@/platform/shutdown'

/**
 * The process: settings, the composed application, the HTTP server, and the three workers, in one
 * process. SIGTERM and SIGINT stop the server first (with a bounded drain timeout), so no new
 * request arrives, then the workers, SPOD catalog sync, and the pool.
 */
const log = createLogger('main')

loadEnvFiles()
const settings = loadSettings(process.env)
logStartupConfig(settings, log)
const application = await createApplication(settings)
application.start()
const server = Bun.serve({
  port: settings.port,
  fetch: application.app.fetch,
  // Bun closes a connection that stays silent for 10 s by default, but an image generation waits
  // up to 2 × 120 s for fal.ai. 255 s is Bun's maximum. (A SPOD catalog sync runs in the
  // background and is polled, so it is not bound by this.)
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

const shutdown = createShutdownHandler({
  stopServer: (closeActiveConnections) => server.stop(closeActiveConnections),
  stopApplication: () => application.stop(),
  exit: (code) => {
    process.exit(code)
  },
  log,
})
process.on('SIGTERM', () => {
  void shutdown('SIGTERM')
})
process.on('SIGINT', () => {
  void shutdown('SIGINT')
})

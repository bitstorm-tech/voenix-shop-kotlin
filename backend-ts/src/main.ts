import { createApplication } from '@/composition'
import { createLogger } from '@/platform/logging'
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
const server = Bun.serve({ port: settings.port, fetch: application.app.fetch })
log.info({ port: server.port }, 'Voenix backend listening')

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

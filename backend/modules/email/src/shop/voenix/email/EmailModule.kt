package shop.voenix.email

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.SweegoEmailDelivery
import shop.voenix.email.outbox.EmailJobRepository
import shop.voenix.email.outbox.EmailWorker
import shop.voenix.email.rendering.EmailRenderer

/**
 * Runtime handle of the Email module. Only [userEmails] and [outbox] are exported capabilities; the
 * handle carries [startWorker] because the background worker must be started exactly once. The
 * worker launches on [ApplicationStarted], after the composition root has finished wiring every
 * queued-source branch, so the first scan never observes a partially bound [QueuedEmailSource].
 * Application shutdown cancels the worker and closes the provider client.
 */
public class EmailModule
internal constructor(
    public val userEmails: UserEmailSender,
    public val outbox: EmailOutbox,
    private val worker: EmailWorker,
    private val delivery: AutoCloseable,
) {
    private var installed = false
    private var workerJob: Job? = null

    internal fun startWorker(application: Application) {
        check(!installed) { "Email module worker is already started" }
        installed = true
        application.monitor.subscribe(ApplicationStarted) {
            // A repeated ApplicationStarted event must never launch a second active worker.
            if (workerJob == null) {
                workerJob = application.launch { worker.run() }
            }
        }
        application.monitor.subscribe(ApplicationStopped) {
            workerJob?.cancel()
            delivery.close()
        }
    }
}

internal fun createEmailModule(
    database: Database,
    settings: EmailSettings,
    source: QueuedEmailSource,
    delivery: EmailDelivery,
    closeableDelivery: AutoCloseable = AutoCloseable {},
): EmailModule {
    val repository = EmailJobRepository(database)
    val renderer = EmailRenderer()
    val service = EmailService(settings, renderer, delivery, repository)
    return EmailModule(
        userEmails = service,
        outbox = service,
        worker = EmailWorker(settings, source, renderer, delivery, repository),
        delivery = closeableDelivery,
    )
}

public fun Application.installEmailModule(
    database: Database,
    settings: EmailSettings,
    source: QueuedEmailSource,
): EmailModule {
    val delivery = SweegoEmailDelivery(settings)
    val module = createEmailModule(database, settings, source, delivery, delivery)
    module.startWorker(this)
    return module
}

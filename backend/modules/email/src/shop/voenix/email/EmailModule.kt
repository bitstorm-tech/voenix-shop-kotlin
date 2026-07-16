package shop.voenix.email

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.SweegoEmailDelivery
import shop.voenix.email.outbox.EmailJobRepository
import shop.voenix.email.outbox.EmailWorker
import shop.voenix.email.rendering.EmailRenderer

public class EmailModule
internal constructor(
    public val userEmails: UserEmailSender,
    public val outbox: EmailOutbox,
    private val worker: EmailWorker,
    private val delivery: AutoCloseable,
) {
    private var workerJob: Job? = null

    internal fun install(application: Application) {
        check(workerJob == null) { "Email module is already installed" }
        workerJob = application.launch { worker.run() }
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
    val retryDelay = Duration.ofMinutes(settings.pollIntervalMinutes.toLong())
    val repository = EmailJobRepository(database, retryDelay)
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
    return createEmailModule(database, settings, source, delivery, delivery).also { module ->
        module.install(this)
    }
}

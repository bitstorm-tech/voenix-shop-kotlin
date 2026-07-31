package shop.voenix

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.EmailOutbox
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmailSource
import shop.voenix.email.UserEmailSender
import shop.voenix.email.installEmailModule
import shop.voenix.production.ProductionModule
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.installProductionModule

/**
 * What the application's email-and-production wiring hands to the modules installed after it.
 *
 * [userEmails] is the direct-delivery capability the account module sends with, [emailOutbox] and
 * [production] are what the order module needs to make a paid order durable, and
 * [bindOrderConfirmations] closes the last open port of the aggregate once the order module exists.
 */
internal class EmailRuntime(
    val userEmails: UserEmailSender,
    val emailOutbox: EmailOutbox,
    val production: ProductionModule,
    private val queuedEmails: AggregatedQueuedEmailSource,
) {
    fun bindOrderConfirmations(source: QueuedEmailSource) {
        queuedEmails.bindOrderConfirmations(source)
    }
}

/**
 * The application's one email-runtime wiring: install the email module exactly once with the
 * aggregated queued source, install the full production module against the returned real
 * [EmailOutbox], and bind the producer-notification resolver. `Application` and the composition
 * integration test share this function, so the test exercises the real wiring instead of mirroring
 * it; only the settings and the [ProductionSource] are injection points.
 */
internal fun Application.installEmailRuntime(
    database: Database,
    emailSettings: EmailSettings,
    productionSettings: ProductionSettings,
    productionSource: ProductionSource,
): EmailRuntime {
    val queuedEmails = AggregatedQueuedEmailSource()
    val email = installEmailModule(database, emailSettings, queuedEmails)
    val production =
        installProductionModule(database, productionSettings, email.outbox, productionSource)
    queuedEmails.bindProducerNotifications(production.producerNotifications)
    return EmailRuntime(
        userEmails = email.userEmails,
        emailOutbox = email.outbox,
        production = production,
        queuedEmails = queuedEmails,
    )
}

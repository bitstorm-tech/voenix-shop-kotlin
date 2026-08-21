package shop.voenix.production

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailSource
import shop.voenix.production.delivery.ProducerNotificationResolver
import shop.voenix.production.delivery.ProductionArtifactGenerator
import shop.voenix.production.delivery.ProductionDeliverer
import shop.voenix.production.delivery.ProductionDeliveryAdapter
import shop.voenix.production.delivery.ProductionDeliveryRepository
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.delivery.ProductionJobRepository
import shop.voenix.production.delivery.ProductionRequestRepository
import shop.voenix.production.delivery.ProductionWorker
import shop.voenix.production.delivery.sftp.SftpProductionDelivery
import shop.voenix.production.delivery.spod.SpodClient
import shop.voenix.production.delivery.spod.SpodOpsAlertResolver
import shop.voenix.production.delivery.spod.SpodOrderRepository
import shop.voenix.production.delivery.spod.SpodOrderSubmitter
import shop.voenix.production.fulfillment.ShipJobInput
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.ProductionPdfService
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Production module. [pdfGenerator] is the public on-demand PDF capability,
 * [outbox] the durable production trigger for the future payment-completion transaction, and
 * [queuedEmails] the resolver for *all three* mail kinds this module owns — producer PDF
 * notifications, customer shipping notifications, and the print-on-demand operations alert — which
 * the application hangs into the aggregated `QueuedEmailSource` of the email module as one branch.
 * The handle carries [startWorker] because the background worker must be started exactly once. The
 * application installs the fully composed module via [installProductionModule], passing a
 * late-bound [ProductionSource] that it binds to the order module right after installing it — until
 * then, and only during startup, a load fails loudly and retryably. Standalone tests assemble the
 * module via [createProductionModule].
 */
public class ProductionModule
internal constructor(
    internal val destinations: ProductionDestinationOperations,
    public val pdfGenerator: ProductionPdfGenerator,
    public val outbox: ProductionOutbox,
    private val emailBranches: ProductionQueuedEmails,
    private val worker: ProductionWorker,
    private val spodClient: SpodClient,
) {
    /** Everything this module resolves for the email outbox, as one source. */
    public val queuedEmails: QueuedEmailSource
        get() = emailBranches

    /**
     * Closes the late branch of [queuedEmails] with the shipping-notification resolver. Called by
     * `installProductionFulfillment`, the only place where the order module's port for it exists.
     */
    internal fun bindShippingNotifications(source: QueuedEmailSource) {
        emailBranches.bindShippingNotifications(source)
    }

    private var workerJob: Job? = null

    /**
     * Starts the background worker and ties the lifetime of the print-on-demand HTTP client to the
     * application: the client owns a connection pool, and the module that built it is the only
     * place that may close it.
     */
    internal fun startWorker(application: Application) {
        check(workerJob == null) { "Production module worker is already started" }
        workerJob = application.launch { worker.run() }
        application.monitor.subscribe(ApplicationStopped) {
            workerJob?.cancel()
            spodClient.close()
        }
    }
}

/** The parameter list is long because the dependencies *are* the list, one per collaborator. */
@Suppress("LongParameterList")
internal fun createProductionModule(
    database: Database,
    artifactRoot: Path,
    deliveryAdapters: List<ProductionDeliveryAdapter> = listOf(SftpProductionDelivery()),
    emailOutbox: EmailOutbox,
    spodClient: SpodClient = SpodClient(),
    spod: ProductionSpodSettings? = null,
    productionSource: ProductionSource,
): ProductionModule {
    val requests = ProductionRequestRepository(database)
    val spodOrders = SpodOrderRepository(database, emailOutbox)
    val renderer = ProductionPdfRenderer()
    val artifacts = ProductionArtifactStore(artifactRoot)
    val deliveries = ProductionDeliveryRepository(database, emailOutbox)
    return ProductionModule(
        destinations = ProductionDestinationService(ProductionDestinationRepository(database)),
        pdfGenerator = ProductionPdfService(productionSource, renderer),
        outbox = ProductionOutbox { orderId -> requests.requestInCurrentTransaction(orderId) },
        emailBranches =
            ProductionQueuedEmails(
                producerNotifications = ProducerNotificationResolver(deliveries, productionSource),
                spodOpsAlerts = SpodOpsAlertResolver(spodOrders, spod?.alertEmail),
            ),
        worker =
            ProductionWorker(
                source = productionSource,
                repository = requests,
                generator =
                    ProductionArtifactGenerator(
                        source = productionSource,
                        jobs = ProductionJobRepository(database),
                        renderer = renderer,
                        artifacts = artifacts,
                    ),
                deliverer =
                    ProductionDeliverer(
                        repository = deliveries,
                        artifacts = artifacts,
                        adapters = deliveryAdapters,
                    ),
                submitter =
                    SpodOrderSubmitter(
                        source = productionSource,
                        orders = spodOrders,
                        client = spodClient,
                    ),
            ),
        spodClient = spodClient,
    )
}

/**
 * The integration-test seam: builds the destination service on [database] and installs the admin
 * destination routes on it, without the worker and the delivery pipeline the full module carries.
 */
internal fun Application.installProductionModule(database: Database) =
    installDestinationRoutes(
        ProductionDestinationService(ProductionDestinationRepository(database))
    )

public fun Application.installProductionModule(
    database: Database,
    settings: ProductionSettings,
    emailOutbox: EmailOutbox,
    source: ProductionSource,
): ProductionModule {
    val module =
        createProductionModule(
            database,
            settings.artifactRoot,
            emailOutbox = emailOutbox,
            spod = settings.spod,
            productionSource = source,
        )
    installDestinationRoutes(module.destinations)
    module.startWorker(this)
    return module
}

public fun RequestValidationConfig.validateProductionRequests() {
    validate<ProductionDestinationInput> { input -> input.toRequestValidationResult() }
    validate<ShipJobInput> { input -> input.toRequestValidationResult() }
}

/**
 * Deployment configuration of the Production module. [artifactRoot] is the private filesystem root
 * for generated production PDFs; the module creates it at installation, so an unusable root fails
 * the application startup instead of the first background generation.
 *
 * [spod] is the print-on-demand half and is `null` in a deployment that has no such supplier. It is
 * not optional in one that does: `installProductionFulfillment` refuses to start when a SPOD
 * destination exists without it, because a channel whose shipments arrive by webhook cannot report
 * a single one without the secret that authorizes the callback.
 */
public class ProductionSettings
internal constructor(
    internal val artifactRoot: Path,
    internal val spod: ProductionSpodSettings? = null,
) {
    override fun toString(): String = "ProductionSettings(artifactRoot=$artifactRoot, spod=$spod)"

    public companion object {
        public fun from(config: ApplicationConfig): ProductionSettings {
            val artifactRoot =
                config
                    .propertyOrNull("production.artifactRoot")
                    ?.getString()
                    ?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: production.artifactRoot")
            return ProductionSettings(
                artifactRoot = Path.of(artifactRoot),
                spod = ProductionSpodSettings.from(config),
            )
        }
    }
}

/**
 * The two values the print-on-demand channel needs beyond its destinations: the secret the
 * partner's webhook authenticates with, and the address that gets the ops alerts.
 *
 * [webhookSecret] is a credential, not configuration: it *is* the last path segment of the callback
 * URL and therefore the only thing standing between the ship transaction and the public internet —
 * the same design the Mollie webhook uses. It has to be long enough to be worth guessing at, and it
 * never appears in a log line, which is what [toString] is about.
 *
 * [alertEmail] is required next to it, because every state this shop cannot resolve by itself ends
 * as a mail to an operator; a webhook that can report a cancellation with nowhere to report it to
 * would silently drop the one message that needs a human.
 */
public class ProductionSpodSettings
internal constructor(webhookSecret: String, alertEmail: String) {
    internal val webhookSecret: String = webhookSecret.trim()
    internal val alertEmail: String = alertEmail.trim()

    init {
        require(this.webhookSecret.length >= MINIMUM_SECRET_LENGTH) {
            "SPOD webhook secret must be at least $MINIMUM_SECRET_LENGTH characters"
        }
        require(this.alertEmail.isNotBlank()) { "SPOD alert e-mail address is required" }
        require(this.alertEmail.none(Char::isISOControl)) {
            "SPOD alert e-mail address must not contain control characters"
        }
    }

    /** Renders no credential: should settings ever be logged, a log is not a secret store. */
    override fun toString(): String =
        "ProductionSpodSettings(alertEmail=$alertEmail, webhookSecret=[REDACTED])"

    internal companion object {
        /**
         * The block, or `null` when this deployment configures no print-on-demand channel at all. A
         * half-filled block is a configuration mistake and fails in the constructor rather than
         * starting a shop whose t-shirt orders can never report a shipment.
         */
        fun from(config: ApplicationConfig): ProductionSpodSettings? {
            val webhookSecret = config.spodValue("webhookSecret")
            val alertEmail = config.spodValue("alertEmail")
            return if (webhookSecret.isEmpty() && alertEmail.isEmpty()) {
                null
            } else {
                ProductionSpodSettings(webhookSecret, alertEmail)
            }
        }

        private fun ApplicationConfig.spodValue(name: String): String =
            propertyOrNull("production.spod.$name")?.getString().orEmpty().trim()

        /** A generated UUID clears it; anything a human types by hand should not. */
        private const val MINIMUM_SECRET_LENGTH = 32
    }
}

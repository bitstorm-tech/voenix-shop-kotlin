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
import shop.voenix.production.fulfillment.ShipJobInput
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.ProductionPdfService
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Production module. [pdfGenerator] is the public on-demand PDF capability,
 * [outbox] the durable production trigger for the future payment-completion transaction, and
 * [queuedEmails] the resolver for *both* mail kinds this module owns — producer PDF notifications
 * and customer shipping notifications — which the application hangs into the aggregated
 * `QueuedEmailSource` of the email module as one branch. The application installs the fully
 * composed module via [installProductionModule], passing a late-bound [ProductionSource] that it
 * binds to the order module right after installing it — until then, and only during startup, a load
 * fails loudly and retryably. Standalone tests assemble the module via [createProductionModule].
 */
public class ProductionModule
internal constructor(
    internal val destinations: ProductionDestinationOperations,
    public val pdfGenerator: ProductionPdfGenerator,
    public val outbox: ProductionOutbox,
    private val emailBranches: ProductionQueuedEmails,
    private val worker: ProductionWorker,
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

    internal fun install(application: Application) {
        check(workerJob == null) { "Production module is already installed" }
        DestinationRoutes.install(application, destinations)
        workerJob = application.launch { worker.run() }
        application.monitor.subscribe(ApplicationStopped) { workerJob?.cancel() }
    }
}

internal fun createProductionModule(
    database: Database,
    artifactRoot: Path,
    deliveryAdapters: List<ProductionDeliveryAdapter> = listOf(SftpProductionDelivery()),
    emailOutbox: EmailOutbox,
    productionSource: ProductionSource,
): ProductionModule {
    val requests = ProductionRequestRepository(database)
    val renderer = ProductionPdfRenderer()
    val artifacts = ProductionArtifactStore(artifactRoot)
    val deliveries = ProductionDeliveryRepository(database, emailOutbox)
    return ProductionModule(
        destinations = ProductionDestinationService(ProductionDestinationRepository(database)),
        pdfGenerator = ProductionPdfService(productionSource, renderer),
        outbox = ProductionOutbox { orderId -> requests.requestInCurrentTransaction(orderId) },
        emailBranches =
            ProductionQueuedEmails(ProducerNotificationResolver(deliveries, productionSource)),
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
            ),
    )
}

internal fun Application.installProductionModule(
    destinations: ProductionDestinationOperations
): Unit = DestinationRoutes.install(this, destinations)

internal fun Application.installProductionModule(database: Database): Unit =
    installProductionModule(ProductionDestinationService(ProductionDestinationRepository(database)))

public fun Application.installProductionModule(
    database: Database,
    settings: ProductionSettings,
    emailOutbox: EmailOutbox,
    source: ProductionSource,
): ProductionModule =
    createProductionModule(
            database,
            settings.artifactRoot,
            emailOutbox = emailOutbox,
            productionSource = source,
        )
        .also { module -> module.install(this) }

public fun RequestValidationConfig.validateProductionRequests(): Unit {
    validate<ProductionDestinationInput> { input -> input.toRequestValidationResult() }
    validate<ShipJobInput> { input -> input.toRequestValidationResult() }
}

/**
 * Deployment configuration of the Production module. [artifactRoot] is the private filesystem root
 * for generated production PDFs; the module creates it at installation, so an unusable root fails
 * the application startup instead of the first background generation.
 */
public class ProductionSettings internal constructor(internal val artifactRoot: Path) {
    public companion object {
        public fun from(config: ApplicationConfig): ProductionSettings {
            val artifactRoot =
                config
                    .propertyOrNull("production.artifactRoot")
                    ?.getString()
                    ?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: production.artifactRoot")
            return ProductionSettings(Path.of(artifactRoot))
        }
    }
}

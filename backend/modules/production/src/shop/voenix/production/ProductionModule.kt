package shop.voenix.production

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.ProductionPdfService
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Production module. [pdfGenerator] is the public on-demand PDF capability,
 * [outbox] the durable production trigger for the future payment-completion transaction, and
 * [producerNotifications] the resolver for producer-PDF notification references that the
 * application hangs into the aggregated `QueuedEmailSource` of the email module. The application
 * installs the fully composed module via [installProductionModule]; until the Order migration
 * supplies a real [ProductionSource], it passes a source that fails loudly and retryably.
 * Standalone tests assemble the module via [createProductionModule].
 */
public class ProductionModule
internal constructor(
    internal val destinations: ProductionDestinationOperations,
    public val pdfGenerator: ProductionPdfGenerator,
    public val outbox: ProductionOutbox,
    public val producerNotifications: QueuedEmailSource,
    private val worker: ProductionWorker,
) {
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
        producerNotifications = ProducerNotificationResolver(deliveries, productionSource),
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
}

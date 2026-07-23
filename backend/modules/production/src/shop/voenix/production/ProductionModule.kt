package shop.voenix.production

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.delivery.ProductionRequestRepository
import shop.voenix.production.delivery.ProductionWorker
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.ProductionPdfService
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Production module. [pdfGenerator] is the public on-demand PDF capability,
 * [outbox] the durable production trigger for the future payment-completion transaction. The
 * application obtains a fully composed module only once a real [ProductionSource] exists (the Order
 * migration). Until then, standalone tests assemble it via [createProductionModule].
 */
public class ProductionModule
internal constructor(
    internal val destinations: ProductionDestinationOperations,
    public val pdfGenerator: ProductionPdfGenerator,
    public val outbox: ProductionOutbox,
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
    productionSource: ProductionSource,
): ProductionModule {
    val requests = ProductionRequestRepository(database)
    return ProductionModule(
        destinations = ProductionDestinationService(ProductionDestinationRepository(database)),
        pdfGenerator = ProductionPdfService(productionSource, ProductionPdfRenderer()),
        outbox = ProductionOutbox { orderId -> requests.requestInCurrentTransaction(orderId) },
        worker = ProductionWorker(productionSource, requests),
    )
}

internal fun Application.installProductionModule(
    destinations: ProductionDestinationOperations
): Unit = DestinationRoutes.install(this, destinations)

public fun Application.installProductionModule(database: Database): Unit =
    installProductionModule(ProductionDestinationService(ProductionDestinationRepository(database)))

public fun RequestValidationConfig.validateProductionRequests(): Unit {
    validate<ProductionDestinationInput> { input -> input.toRequestValidationResult() }
}

package shop.voenix.production.fulfillment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.pdf.ProductionArtifactLoadResult
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary

/**
 * Assembles a fulfillment page out of the three things it is made of: the job rows, the order
 * headers behind them, and the item lines their artifact was rendered from.
 *
 * The batching rule is the whole performance contract of this class, and it is visible in the code:
 * a page reads its jobs once, resolves *all* their order ids with one [FulfillmentOrderSource]
 * call, reads *all* their items with one repository call, and — on the admin list, the only one
 * that shows supplier names — resolves *all* their supplier ids with one [SupplierReader] call. No
 * loop here ever calls out.
 *
 * A job whose order header the order module cannot answer for is dropped from the page and logged.
 * The foreign keys make that impossible in practice; rendering an address-less shipping label would
 * be worse than a missing row.
 */
internal class FulfillmentService(
    private val repository: FulfillmentRepository,
    private val orders: FulfillmentOrderSource,
    private val suppliers: SupplierReader,
    private val artifacts: ProductionArtifactStore,
) : FulfillmentOperations {
    override suspend fun identity(supplierId: Long): SupplierIdentityView {
        val supplier =
            checkNotNull(suppliers.find(setOf(supplierId))[supplierId]) {
                "Supplier $supplierId has a login but no supplier row"
            }
        return SupplierIdentityView(supplierId = supplier.id, supplierName = supplier.name)
    }

    override suspend fun supplierJobs(
        supplierId: Long,
        status: FulfillmentJobStatus,
    ): List<SupplierJobView> {
        val page = page(status, supplierId) ?: return emptyList()
        return page.jobs.mapNotNull { job ->
            page.headerOf(job)?.let { header ->
                SupplierJobView(
                    jobId = job.id,
                    orderId = header.orderId,
                    orderDate = header.orderDate.toString(),
                    customerFirstName = header.customerFirstName,
                    customerLastName = header.customerLastName,
                    shippingStreet = header.shippingStreet,
                    shippingHouseNumber = header.shippingHouseNumber,
                    shippingPostalCode = header.shippingPostalCode,
                    shippingCity = header.shippingCity,
                    shippingCountry = header.shippingCountry,
                    items = page.itemsOf(job),
                    pdfAvailable = job.generatedAt != null,
                    shippedAt = job.shippedAt?.toInstant(),
                    shippingCarrier = job.shippingCarrier,
                    trackingNumber = job.trackingNumber,
                )
            }
        }
    }

    override suspend fun adminJobs(
        status: FulfillmentJobStatus,
        supplierId: Long?,
    ): List<AdminJobView> {
        val page = page(status, supplierId) ?: return emptyList()
        val names =
            suppliers.find(page.jobs.mapTo(mutableSetOf(), StoredFulfillmentJob::supplierId))
        return page.jobs.mapNotNull { job ->
            page.headerOf(job)?.let { header ->
                AdminJobView(
                    jobId = job.id,
                    orderId = header.orderId,
                    orderDate = header.orderDate.toString(),
                    supplier =
                        AdminJobView.Supplier(
                            id = job.supplierId,
                            name = names[job.supplierId]?.let(SupplierSummary::name),
                        ),
                    customerFirstName = header.customerFirstName,
                    customerLastName = header.customerLastName,
                    shippingStreet = header.shippingStreet,
                    shippingHouseNumber = header.shippingHouseNumber,
                    shippingPostalCode = header.shippingPostalCode,
                    shippingCity = header.shippingCity,
                    shippingCountry = header.shippingCountry,
                    items = page.itemsOf(job),
                    pdfAvailable = job.generatedAt != null,
                    generationAttemptCount = job.generationAttemptCount,
                    lastGenerationErrorCode = job.lastGenerationErrorCode,
                    shippedAt = job.shippedAt?.toInstant(),
                    shippedByUserId = job.shippedByUserId,
                    shippingCarrier = job.shippingCarrier,
                    trackingNumber = job.trackingNumber,
                )
            }
        }
    }

    /**
     * Loads the stored artifact and verifies it against the digest written at generation time, so a
     * download either ships exactly the generated bytes or says why it cannot.
     */
    override suspend fun artifact(jobId: Long, supplierScope: Long?): FulfillmentArtifactResult {
        val job = repository.job(jobId, supplierScope) ?: return FulfillmentArtifactResult.NotFound
        val sha256 = job.contentSha256 ?: return FulfillmentArtifactResult.NotGenerated
        val loaded = withContext(Dispatchers.IO) { artifacts.load(job.id, job.fileName, sha256) }
        return when (loaded) {
            is ProductionArtifactLoadResult.Loaded ->
                FulfillmentArtifactResult.Loaded(job.fileName, loaded.bytes)
            ProductionArtifactLoadResult.Missing -> {
                logger.error("Production job {} has a digest but no artifact file", job.id)
                FulfillmentArtifactResult.Missing
            }
            is ProductionArtifactLoadResult.DigestMismatch -> {
                logger.error(
                    "Production job {} artifact does not match its recorded digest",
                    job.id,
                )
                FulfillmentArtifactResult.DigestMismatch
            }
        }
    }

    /** Reads one page's jobs plus the two batches every view of them is built from. */
    private suspend fun page(status: FulfillmentJobStatus, supplierId: Long?): Page? {
        val jobs = repository.jobs(status, supplierId)
        if (jobs.isEmpty()) return null
        return Page(
            jobs = jobs,
            headers = orders.find(jobs.mapTo(mutableSetOf(), StoredFulfillmentJob::orderId)),
            items = repository.items(jobs.mapTo(mutableSetOf(), StoredFulfillmentJob::id)),
        )
    }

    private class Page(
        val jobs: List<StoredFulfillmentJob>,
        val headers: Map<Long, FulfillmentOrder>,
        val items: Map<Long, List<StoredFulfillmentJob.Item>>,
    ) {
        fun headerOf(job: StoredFulfillmentJob): FulfillmentOrder? {
            val header = headers[job.orderId]
            if (header == null) {
                logger.error(
                    "Production job {} names order {}, which no longer exists",
                    job.id,
                    job.orderId,
                )
            }
            return header
        }

        fun itemsOf(job: StoredFulfillmentJob): List<FulfillmentItemView> =
            items[job.id].orEmpty().sortedBy(StoredFulfillmentJob.Item::position).map { item ->
                FulfillmentItemView(
                    articleName = item.articleName,
                    variantName = item.variantName,
                    supplierArticleNumber = item.supplierArticleNumber,
                    quantity = item.quantity,
                )
            }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(FulfillmentService::class.java)
    }
}

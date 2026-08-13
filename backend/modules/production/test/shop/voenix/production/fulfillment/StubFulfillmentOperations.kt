package shop.voenix.production.fulfillment

import java.time.Instant

/**
 * Records what the routes ask for, so a route test can prove that a rejected request never reached
 * the operations at all — and that an accepted one was asked with the scope the protection resolved
 * rather than with anything a caller sent.
 */
internal class StubFulfillmentOperations : FulfillmentOperations {
    val calls = mutableListOf<String>()
    var artifact: FulfillmentArtifactResult = FulfillmentArtifactResult.NotFound

    /** The refusal the next ship request answers with; `null` means the shipment succeeds. */
    var shipFailure: ShipResult<Nothing>? = null

    override suspend fun identity(supplierId: Long): SupplierIdentityView {
        calls += "identity($supplierId)"
        return SupplierIdentityView(supplierId = supplierId, supplierName = "Supplier $supplierId")
    }

    override suspend fun supplierJobs(
        supplierId: Long,
        status: FulfillmentJobStatus,
    ): List<SupplierJobView> {
        calls += "supplierJobs($supplierId, $status)"
        return emptyList()
    }

    override suspend fun adminJobs(
        status: FulfillmentJobStatus,
        supplierId: Long?,
    ): List<AdminJobView> {
        calls += "adminJobs($status, $supplierId)"
        return emptyList()
    }

    override suspend fun artifact(jobId: Long, supplierScope: Long?): FulfillmentArtifactResult {
        calls += "artifact($jobId, $supplierScope)"
        return artifact
    }

    override suspend fun shipAsSupplier(
        jobId: Long,
        supplierId: Long,
        actorUserId: Long,
        shipment: Shipment,
    ): ShipResult<SupplierJobView> {
        calls += "shipAsSupplier($jobId, $supplierId, $actorUserId, ${shipment.describe()})"
        return shipFailure ?: ShipResult.Shipped(supplierJobView(jobId, shipment))
    }

    override suspend fun shipAsAdmin(
        jobId: Long,
        actorUserId: Long,
        shipment: Shipment,
    ): ShipResult<AdminJobView> {
        calls += "shipAsAdmin($jobId, $actorUserId, ${shipment.describe()})"
        return shipFailure ?: ShipResult.Shipped(adminJobView(jobId, shipment))
    }

    private fun supplierJobView(jobId: Long, shipment: Shipment): SupplierJobView =
        SupplierJobView(
            jobId = jobId,
            orderId = ORDER_ID,
            orderDate = ORDER_DATE,
            customerFirstName = "Erika",
            customerLastName = "Musterfrau",
            shippingStreet = "Musterstraße",
            shippingHouseNumber = "1",
            shippingPostalCode = "12345",
            shippingCity = "Berlin",
            shippingCountry = "DE",
            items = emptyList(),
            pdfAvailable = true,
            shippedAt = SHIPPED_AT,
            shippingCarrier = shipment.carrier?.name,
            trackingNumber = shipment.trackingNumber,
        )

    private fun adminJobView(jobId: Long, shipment: Shipment): AdminJobView =
        AdminJobView(
            jobId = jobId,
            orderId = ORDER_ID,
            orderDate = ORDER_DATE,
            supplier = AdminJobView.Supplier(id = 1, name = "Alpha"),
            customerFirstName = "Erika",
            customerLastName = "Musterfrau",
            shippingStreet = "Musterstraße",
            shippingHouseNumber = "1",
            shippingPostalCode = "12345",
            shippingCity = "Berlin",
            shippingCountry = "DE",
            items = emptyList(),
            pdfAvailable = true,
            generationAttemptCount = 1,
            lastGenerationErrorCode = null,
            shippedAt = SHIPPED_AT,
            shippedByUserId = 42,
            shippingCarrier = shipment.carrier?.name,
            trackingNumber = shipment.trackingNumber,
        )

    private fun Shipment.describe(): String = "${carrier?.name}, $trackingNumber"

    private companion object {
        const val ORDER_ID = 70L
        const val ORDER_DATE = "2026-07-16"
        val SHIPPED_AT: Instant = Instant.parse("2026-08-13T10:15:30Z")
    }
}

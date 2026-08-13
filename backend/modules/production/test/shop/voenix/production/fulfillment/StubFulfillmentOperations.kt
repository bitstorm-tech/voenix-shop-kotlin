package shop.voenix.production.fulfillment

/**
 * Records what the routes ask for, so a route test can prove that a rejected request never reached
 * the operations at all — and that an accepted one was asked with the scope the protection resolved
 * rather than with anything a caller sent.
 */
internal class StubFulfillmentOperations : FulfillmentOperations {
    val calls = mutableListOf<String>()
    var artifact: FulfillmentArtifactResult = FulfillmentArtifactResult.NotFound

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
}

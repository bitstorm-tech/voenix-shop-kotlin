package shop.voenix.production.fulfillment

/**
 * What the fulfillment routes may ask for, and the seam the route tests stub.
 *
 * Every method takes the caller's scope as a parameter instead of reading it from a call: the
 * supplier id comes from the route protection, and an admin passes `null` where a supplier passes
 * its own id. Authorization stays an HTTP concern above this interface, and no implementation can
 * accidentally answer for a scope it was not given.
 */
internal interface FulfillmentOperations {
    /** The calling supplier's own identity, for the header of its surface. */
    suspend fun identity(supplierId: Long): SupplierIdentityView

    /** One supplier's jobs in the requested state; never another supplier's. */
    suspend fun supplierJobs(supplierId: Long, status: FulfillmentJobStatus): List<SupplierJobView>

    /** Every supplier's jobs in the requested state, optionally narrowed to one supplier. */
    suspend fun adminJobs(status: FulfillmentJobStatus, supplierId: Long?): List<AdminJobView>

    /**
     * The stored artifact of one job, verified against its recorded digest. [supplierScope] is the
     * supplier a supplier caller is bound to, or `null` for an admin who may read every job.
     */
    suspend fun artifact(jobId: Long, supplierScope: Long?): FulfillmentArtifactResult
}

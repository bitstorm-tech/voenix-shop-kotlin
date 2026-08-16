package shop.voenix.account

import shop.voenix.operation.OperationResult

/**
 * The admin route test's stand-in for [SupplierLoginService]. It counts how often an operation was
 * reached, which is how that test proves a rejected request — no session, wrong role, bad CSRF,
 * invalid body or query — never got that far, and it lets each test dictate the outcome it wants
 * mapped to a status.
 */
internal class StubSupplierLoginOperations : SupplierLoginOperations {
    var operationCalls = 0
        private set

    var createSupplierLoginResult: CreateSupplierLoginResult =
        CreateSupplierLoginResult.Created(
            SupplierLoginView(
                userId = 12,
                email = "logistik@lieferant.example",
                supplierId = 3,
                createdAt = "2026-08-13T10:00:00Z",
            )
        )
    var listSupplierLoginsResult: OperationResult<List<SupplierLoginView>> =
        OperationResult.Success(emptyList())
    var deleteSupplierLoginResult: OperationResult<Unit> = OperationResult.Success(Unit)

    /** The supplier id the last list call was scoped to, so a test can pin the query binding. */
    var listedSupplierId: Long? = null
        private set

    /** The user id the last delete call named, so a test can pin the path binding. */
    var deletedUserId: Long? = null
        private set

    override suspend fun createSupplierLogin(
        input: CreateSupplierLoginInput
    ): CreateSupplierLoginResult {
        operationCalls++
        return createSupplierLoginResult
    }

    override suspend fun listSupplierLogins(
        supplierId: Long
    ): OperationResult<List<SupplierLoginView>> {
        operationCalls++
        listedSupplierId = supplierId
        return listSupplierLoginsResult
    }

    override suspend fun deleteSupplierLogin(userId: Long): OperationResult<Unit> {
        operationCalls++
        deletedUserId = userId
        return deleteSupplierLoginResult
    }
}

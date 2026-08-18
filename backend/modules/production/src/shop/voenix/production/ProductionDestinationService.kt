package shop.voenix.production

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.production.delivery.ProductionDestinationDeleteResult
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.delivery.ProductionDestinationWrite
import shop.voenix.production.delivery.ProductionDestinationWriteResult
import shop.voenix.production.delivery.StoredProductionDestination
import shop.voenix.validation.buildValidationErrors

/**
 * Validates and normalizes admin destination writes and hands the repository its write model.
 *
 * `create` and `update` call `input.validate()` themselves even though the application installs the
 * `RequestValidation` plugin with `validateProductionRequests()` (see `Application.kt`): the
 * integration-test seam `installProductionModule(database)` wires the module without that plugin,
 * so the service call is the only validation on that path.
 */
internal class ProductionDestinationService(
    private val repository: ProductionDestinationRepository
) : ProductionDestinationOperations {
    override suspend fun list(): OperationResult<List<ProductionDestination>> =
        logger.databaseOperation(
            "Database error while listing production destinations",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list().map(StoredProductionDestination::toApiModel))
        }

    override suspend fun get(id: Long): OperationResult<ProductionDestination> =
        logger.databaseOperation(
            "Database error while reading production destination $id",
            OperationResult.UnexpectedFailure,
        ) {
            val stored = repository.find(id) ?: return@databaseOperation OperationResult.NotFound
            OperationResult.Success(stored.toApiModel())
        }

    override suspend fun create(
        input: ProductionDestinationInput
    ): OperationResult<ProductionDestination> {
        val password = input.newPassword()
        val errors = buildValidationErrors {
            addAll(input.validate())
            if (password == null) {
                add("password", "Password is required")
            }
        }
        if (errors.isNotEmpty() || password == null) return OperationResult.Invalid(errors)

        val write = input.toWrite()
        return logger.databaseOperation(
            "Database error while creating production destination for supplier " +
                "${write.supplierId}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(write, password).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
    ): OperationResult<ProductionDestination> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toWrite()
        val newPassword = input.newPassword()
        return logger.databaseOperation(
            "Database error while updating production destination $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, write, newPassword).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting production destination $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                ProductionDestinationDeleteResult.Deleted -> OperationResult.Success(Unit)
                ProductionDestinationDeleteResult.NotFound -> OperationResult.NotFound
                ProductionDestinationDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    /**
     * Turns a validated request body into the repository's write model: required fields are present
     * (`validate()` ran first, so the `checkNotNull` calls only state that), text is trimmed, and
     * the optional fields fall back to their defaults.
     *
     * The password is not part of the write model — see [newPassword].
     */
    private fun ProductionDestinationInput.toWrite(): ProductionDestinationWrite =
        ProductionDestinationWrite(
            supplierId = checkNotNull(supplierId),
            channel = checkNotNull(channel).trim(),
            label = checkNotNull(label).trim(),
            enabled = enabled ?: true,
            host = checkNotNull(host).trim(),
            port = port ?: DEFAULT_PORT,
            username = checkNotNull(username).trim(),
            hostKeyFingerprint = checkNotNull(hostKeyFingerprint).trim(),
            remotePath = remotePath.normalizedOptional() ?: DEFAULT_REMOTE_PATH,
            timeoutSeconds = checkNotNull(timeoutSeconds),
            notificationEmail = notificationEmail.normalizedOptional(),
            notificationName = notificationName.normalizedOptional(),
        )

    /**
     * The password to store, or `null` when the request does not set one — create then answers
     * "Password is required", update keeps the stored password.
     *
     * A non-blank password is never trimmed: leading or trailing spaces are part of the secret, and
     * the SFTP server expects the bytes the admin typed.
     */
    private fun ProductionDestinationInput.newPassword(): String? = password?.ifBlank { null }

    private fun String?.normalizedOptional(): String? = this?.trim()?.ifBlank { null }

    private fun ProductionDestinationWriteResult.toOperationResult():
        OperationResult<ProductionDestination> =
        when (this) {
            is ProductionDestinationWriteResult.Stored ->
                OperationResult.Success(destination.toApiModel())
            ProductionDestinationWriteResult.NotFound -> OperationResult.NotFound
            ProductionDestinationWriteResult.SupplierNotFound ->
                OperationResult.Invalid(unknownSupplierErrors)
        }

    private companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_REMOTE_PATH = "/"
        val logger: Logger = LoggerFactory.getLogger(ProductionDestinationService::class.java)
        val unknownSupplierErrors: Map<String, List<String>> =
            mapOf("supplierId" to listOf("Supplier not found"))
    }
}

internal interface ProductionDestinationOperations {
    suspend fun list(): OperationResult<List<ProductionDestination>>

    suspend fun get(id: Long): OperationResult<ProductionDestination>

    suspend fun create(input: ProductionDestinationInput): OperationResult<ProductionDestination>

    suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
    ): OperationResult<ProductionDestination>

    suspend fun delete(id: Long): OperationResult<Unit>
}

private fun StoredProductionDestination.toApiModel(): ProductionDestination =
    ProductionDestination(
        id = id,
        supplierId = supplierId,
        channel = channel,
        label = label,
        enabled = enabled,
        host = host,
        port = port,
        username = username,
        hostKeyFingerprint = hostKeyFingerprint,
        remotePath = remotePath,
        timeoutSeconds = timeoutSeconds,
        notificationEmail = notificationEmail,
        notificationName = notificationName,
    )

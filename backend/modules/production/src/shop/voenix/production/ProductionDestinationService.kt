package shop.voenix.production

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.production.delivery.ProductionDestinationDeleteResult
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.delivery.ProductionDestinationWriteResult
import shop.voenix.production.delivery.StoredProductionDestination

internal class ProductionDestinationService(
    private val repository: ProductionDestinationRepository
) : ProductionDestinationOperations {
    override suspend fun list(): OperationResult<List<ProductionDestination>> =
        databaseOperation("Database error while listing production destinations") {
            OperationResult.Success(repository.list().map(StoredProductionDestination::toApiModel))
        }

    override suspend fun get(id: Long): OperationResult<ProductionDestination> =
        databaseOperation("Database error while reading production destination $id") {
            val stored = repository.find(id) ?: return@databaseOperation OperationResult.NotFound
            OperationResult.Success(stored.toApiModel())
        }

    override suspend fun create(
        input: ProductionDestinationInput
    ): OperationResult<ProductionDestination> {
        val errors = buildMap {
            putAll(input.validate())
            if (input.password.isNullOrBlank()) {
                put("password", listOf("Password is required"))
            }
        }
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation(
            "Database error while creating production destination for supplier " +
                "${normalized.supplierId}"
        ) {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
    ): OperationResult<ProductionDestination> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating production destination $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting production destination $id") {
            when (repository.delete(id)) {
                ProductionDestinationDeleteResult.Deleted -> OperationResult.Success(Unit)
                ProductionDestinationDeleteResult.NotFound -> OperationResult.NotFound
                ProductionDestinationDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    /**
     * A blank or absent password never reaches the database: create requires one beforehand, and
     * update keeps the stored password when none is provided.
     */
    private fun ProductionDestinationInput.normalized(): ProductionDestinationInput =
        copy(
            channel = checkNotNull(channel).trim(),
            label = checkNotNull(label).trim(),
            enabled = enabled ?: true,
            host = checkNotNull(host).trim(),
            port = port ?: DEFAULT_PORT,
            username = checkNotNull(username).trim(),
            password = password?.ifBlank { null },
            hostKeyFingerprint = checkNotNull(hostKeyFingerprint).trim(),
            remotePath = remotePath.normalizedOptional() ?: DEFAULT_REMOTE_PATH,
            notificationEmail = notificationEmail.normalizedOptional(),
            notificationName = notificationName.normalizedOptional(),
        )

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

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_REMOTE_PATH = "/"
        val logger: Logger = LoggerFactory.getLogger(ProductionDestinationService::class.java)
        val unknownSupplierErrors: Map<String, List<String>> =
            mapOf("supplierId" to listOf("Supplier not found"))
    }
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

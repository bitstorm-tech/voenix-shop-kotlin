package shop.voenix.production

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.production.delivery.ProductionChannels
import shop.voenix.production.delivery.ProductionDestinationDeleteResult
import shop.voenix.production.delivery.ProductionDestinationDetailWrite
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.delivery.ProductionDestinationWrite
import shop.voenix.production.delivery.ProductionDestinationWriteResult
import shop.voenix.production.delivery.StoredProductionDestination
import shop.voenix.production.delivery.StoredProductionDestinationDetail
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
        val secret = input.newSecret()
        val errors = buildValidationErrors {
            addAll(input.validate())
            if (secret == null) {
                addAll(input.missingSecretErrors())
            }
        }
        if (errors.isNotEmpty() || secret == null) return OperationResult.Invalid(errors)

        val write = input.toWrite()
        return logger.databaseOperation(
            "Database error while creating production destination for supplier " +
                "${write.supplierId}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(write, secret).toOperationResult(input)
        }
    }

    override suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
    ): OperationResult<ProductionDestination> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toWrite()
        val newSecret = input.newSecret()
        return logger.databaseOperation(
            "Database error while updating production destination $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, write, newSecret).toOperationResult(input)
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
     * The channel's secret is not part of the write model — see [newSecret].
     */
    private fun ProductionDestinationInput.toWrite(): ProductionDestinationWrite =
        ProductionDestinationWrite(
            supplierId = checkNotNull(supplierId),
            label = checkNotNull(label).trim(),
            enabled = enabled ?: true,
            notificationEmail = notificationEmail.normalizedOptional(),
            notificationName = notificationName.normalizedOptional(),
            detail = toDetailWrite(),
        )

    private fun ProductionDestinationInput.toDetailWrite(): ProductionDestinationDetailWrite =
        when (checkNotNull(channel).trim()) {
            ProductionChannels.SFTP -> checkNotNull(sftp).toDetailWrite()
            else -> checkNotNull(spod).toDetailWrite()
        }

    private fun SftpDestinationInput.toDetailWrite(): ProductionDestinationDetailWrite.Sftp =
        ProductionDestinationDetailWrite.Sftp(
            host = checkNotNull(host).trim(),
            port = port ?: DEFAULT_PORT,
            username = checkNotNull(username).trim(),
            hostKeyFingerprint = checkNotNull(hostKeyFingerprint).trim(),
            remotePath = remotePath.normalizedOptional() ?: DEFAULT_REMOTE_PATH,
            timeoutSeconds = checkNotNull(timeoutSeconds),
        )

    private fun SpodDestinationInput.toDetailWrite(): ProductionDestinationDetailWrite.Spod =
        ProductionDestinationDetailWrite.Spod(
            environment = checkNotNull(environment),
            timeoutSeconds = checkNotNull(timeoutSeconds),
        )

    /**
     * The secret of the body's channel — the SFTP password or the SPOD access token — or `null`
     * when the request does not set one: create then reports it as required, replace keeps the
     * stored secret.
     *
     * A non-blank secret is never trimmed: leading or trailing spaces are part of it, and the
     * remote system expects the characters the admin typed.
     */
    private fun ProductionDestinationInput.newSecret(): String? =
        when (channel?.trim()) {
            ProductionChannels.SFTP -> sftp?.password
            ProductionChannels.SPOD -> spod?.accessToken
            else -> null
        }?.ifBlank { null }

    /** Names the missing secret in the field the body carries it in. */
    private fun ProductionDestinationInput.missingSecretErrors(): Map<String, List<String>> =
        when (channel?.trim()) {
            ProductionChannels.SPOD -> spodSecretErrors
            else -> sftpSecretErrors
        }

    private fun String?.normalizedOptional(): String? = this?.trim()?.ifBlank { null }

    private fun ProductionDestinationWriteResult.toOperationResult(
        input: ProductionDestinationInput
    ): OperationResult<ProductionDestination> =
        when (this) {
            is ProductionDestinationWriteResult.Stored ->
                OperationResult.Success(destination.toApiModel())
            ProductionDestinationWriteResult.NotFound -> OperationResult.NotFound
            ProductionDestinationWriteResult.SupplierNotFound ->
                OperationResult.Invalid(unknownSupplierErrors)
            ProductionDestinationWriteResult.EnabledSpodExists ->
                OperationResult.Invalid(enabledSpodErrors)
            ProductionDestinationWriteResult.SecretRequired ->
                OperationResult.Invalid(input.missingSecretErrors())
        }

    private companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_REMOTE_PATH = "/"
        val logger: Logger = LoggerFactory.getLogger(ProductionDestinationService::class.java)
        val unknownSupplierErrors: Map<String, List<String>> =
            mapOf("supplierId" to listOf("Supplier not found"))
        val enabledSpodErrors: Map<String, List<String>> =
            mapOf(
                "channel" to
                    listOf("Supplier already has an enabled SPOD destination; disable it first")
            )
        val sftpSecretErrors: Map<String, List<String>> =
            mapOf("sftp.password" to listOf("Password is required"))
        val spodSecretErrors: Map<String, List<String>> =
            mapOf("spod.accessToken" to listOf("AccessToken is required"))
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
        notificationEmail = notificationEmail,
        notificationName = notificationName,
        sftp = (detail as? StoredProductionDestinationDetail.Sftp)?.toApiModel(),
        spod = (detail as? StoredProductionDestinationDetail.Spod)?.toApiModel(),
    )

private fun StoredProductionDestinationDetail.Sftp.toApiModel(): SftpDestinationDetails =
    SftpDestinationDetails(
        host = host,
        port = port,
        username = username,
        hostKeyFingerprint = hostKeyFingerprint,
        remotePath = remotePath,
        timeoutSeconds = timeoutSeconds,
    )

private fun StoredProductionDestinationDetail.Spod.toApiModel(): SpodDestinationDetails =
    SpodDestinationDetails(environment = environment, timeoutSeconds = timeoutSeconds)

package shop.voenix.vat

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class VatService(private val repository: VatRepository) : VatOperations {
    override suspend fun list(): OperationResult<List<Vat>> =
        logger.databaseOperation(
            "Database error while listing VAT entries",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<Vat> =
        logger.databaseOperation(
            "Database error while reading VAT entry $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.find(id)?.let { OperationResult.Success(it) } ?: OperationResult.NotFound
        }

    override suspend fun create(input: VatInput): OperationResult<Vat> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toVatWrite()
        return logger.databaseOperation(
            "Database error while creating VAT entry ${write.name}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(write).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: VatInput,
    ): OperationResult<Vat> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toVatWrite()
        return logger.databaseOperation(
            "Database error while updating VAT entry $id to ${write.name}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, write).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting VAT entry $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                VatDeleteResult.Deleted -> OperationResult.Success(Unit)
                VatDeleteResult.NotFound -> OperationResult.NotFound
                VatDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    private fun VatInput.toVatWrite(): VatWrite =
        VatWrite(
            name = checkNotNull(name).trim(),
            percent = checkNotNull(percent),
            description = description?.trim()?.ifBlank { null },
            isDefault = isDefault,
        )

    private fun VatWriteResult.toOperationResult(): OperationResult<Vat> =
        when (this) {
            is VatWriteResult.Stored -> OperationResult.Success(vat)
            VatWriteResult.NotFound -> OperationResult.NotFound
            VatWriteResult.Conflict -> OperationResult.Conflict
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(VatService::class.java)
    }
}

public interface VatOperations {
    public suspend fun list(): OperationResult<List<Vat>>

    public suspend fun get(id: Long): OperationResult<Vat>

    public suspend fun create(input: VatInput): OperationResult<Vat>

    public suspend fun update(
        id: Long,
        input: VatInput,
    ): OperationResult<Vat>

    public suspend fun delete(id: Long): OperationResult<Unit>
}

public interface VatReader {
    public suspend fun list(): List<Vat>

    public suspend fun find(ids: Set<Long>): Map<Long, Vat>
}

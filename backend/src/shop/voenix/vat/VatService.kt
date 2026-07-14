package shop.voenix.vat

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

class VatService(private val repository: VatRepository) : VatOperations {
    override suspend fun list(): OperationResult<List<Vat>> =
        databaseOperation("Database error while listing VAT entries") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<Vat> =
        databaseOperation("Database error while reading VAT entry $id") {
            repository.find(id)?.let { OperationResult.Success(it) } ?: OperationResult.NotFound
        }

    override suspend fun create(input: VatInput): OperationResult<Vat> {
        val errors = VatInputValidator.validate(input)
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toVatWrite()
        return databaseOperation("Database error while creating VAT entry ${write.name}") {
            repository.insert(write).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: VatInput,
    ): OperationResult<Vat> {
        val errors = VatInputValidator.validate(input)
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val write = input.toVatWrite()
        return databaseOperation("Database error while updating VAT entry $id to ${write.name}") {
            repository.update(id, write).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting VAT entry $id") {
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

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> = runCatching {
        operation()
    }
        .getOrElse { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            logger.error(message, failure)
            OperationResult.UnexpectedFailure
        }

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

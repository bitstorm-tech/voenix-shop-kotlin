package shop.voenix.vat

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class VatService(private val repository: VatRepository) : VatOperations {
    override suspend fun list(): VatResult<List<Vat>> =
        databaseOperation("Database error while listing VAT entries") {
            VatResult.Success(repository.list())
        }

    override suspend fun get(id: Long): VatResult<Vat> =
        databaseOperation("Database error while reading VAT entry $id") {
            repository.find(id)?.let { VatResult.Success(it) } ?: VatResult.NotFound
        }

    override suspend fun create(input: VatInput): VatResult<Vat> {
        val errors = VatInputValidator.validate(input)
        if (errors.isNotEmpty()) return VatResult.Invalid(errors)

        val write = input.toVatWrite()
        return databaseOperation("Database error while creating VAT entry ${write.name}") {
            repository.insert(write).toVatResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: VatInput,
    ): VatResult<Vat> {
        val errors = VatInputValidator.validate(input)
        if (errors.isNotEmpty()) return VatResult.Invalid(errors)

        val write = input.toVatWrite()
        return databaseOperation("Database error while updating VAT entry $id to ${write.name}") {
            repository.update(id, write).toVatResult()
        }
    }

    override suspend fun delete(id: Long): VatResult<Unit> =
        databaseOperation("Database error while deleting VAT entry $id") {
            if (repository.delete(id) == 0) VatResult.NotFound else VatResult.Success(Unit)
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
        operation: suspend () -> VatResult<T>,
    ): VatResult<T> = runCatching {
        operation()
    }
        .getOrElse { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            logger.error(message, failure)
            VatResult.DatabaseError
        }

    private fun VatWriteResult.toVatResult(): VatResult<Vat> =
        when (this) {
            is VatWriteResult.Stored -> VatResult.Success(vat)
            VatWriteResult.NotFound -> VatResult.NotFound
            VatWriteResult.Conflict -> VatResult.Conflict
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(VatService::class.java)
    }
}

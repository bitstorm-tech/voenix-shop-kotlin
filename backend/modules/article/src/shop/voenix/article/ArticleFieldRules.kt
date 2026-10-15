package shop.voenix.article

import shop.voenix.operation.OperationResult
import shop.voenix.validation.ValidationErrorsBuilder

/**
 * The field rules the article inputs share.
 *
 * They are module-local on purpose: there is no platform-wide `requiredText` or `positiveId`,
 * because a message text is part of the HTTP contract of the module that sends it and the modules
 * do not word these rules alike. Inside this module they do — the mug and the SPOD slice were
 * written from the same matrix — so a single declaration here is what keeps them from drifting
 * apart silently.
 *
 * [field] is the key the error is reported under, which for an entry of a variant array is the
 * whole path including the index, such as `mugVariants[0].name`.
 */
internal fun ValidationErrorsBuilder.requiredText(
    field: String,
    displayName: String,
    value: String?,
    maximumLength: Int,
) {
    when {
        value.isNullOrBlank() -> add(field, "$displayName is required")
        value.trim().length > maximumLength ->
            add(field, "$displayName must be at most $maximumLength characters")
    }
}

/** An optional id that, when it is submitted at all, has to be one a row can carry. */
internal fun ValidationErrorsBuilder.positiveId(
    field: String,
    displayName: String,
    value: Long?,
) {
    if (value != null && value <= 0) add(field, "$displayName must be positive")
}

/**
 * The rejection of a write that only one field is to blame for — a category that does not exist, an
 * image name the storage does not know. It is the shape the routes answer as a validation failure,
 * so a rule the database or the storage owns reaches the client the same way an input rule does.
 */
internal fun fieldError(
    field: String,
    message: String,
): OperationResult<Nothing> = OperationResult.Invalid(mapOf(field to listOf(message)))

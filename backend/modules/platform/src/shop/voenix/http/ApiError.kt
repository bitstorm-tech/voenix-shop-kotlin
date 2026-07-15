package shop.voenix.http

import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors

@Serializable
public data class ApiError(
    public val message: String,
    public val errors: ValidationErrors = emptyMap(),
)

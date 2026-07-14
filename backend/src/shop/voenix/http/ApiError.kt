package shop.voenix.http

import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors

@Serializable
data class ApiError(
    val message: String,
    val errors: ValidationErrors = emptyMap(),
)

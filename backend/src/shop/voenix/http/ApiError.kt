package shop.voenix.http

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val message: String,
    val errors: Map<String, List<String>> = emptyMap(),
)

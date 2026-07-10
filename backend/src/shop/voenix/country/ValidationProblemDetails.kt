package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class ValidationProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val errors: Map<String, List<String>>,
    val traceId: String,
)

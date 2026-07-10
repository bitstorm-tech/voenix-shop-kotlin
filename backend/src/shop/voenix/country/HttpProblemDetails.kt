package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class HttpProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val traceId: String,
)

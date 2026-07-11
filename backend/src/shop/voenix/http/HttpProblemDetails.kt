package shop.voenix.http

import kotlinx.serialization.Serializable

@Serializable
internal data class HttpProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val traceId: String,
)

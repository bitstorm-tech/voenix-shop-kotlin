package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class ProblemDetails(
    val status: Int,
    val detail: String,
)

package shop.voenix.article.category

import kotlinx.serialization.Serializable

/**
 * The single admin representation of a category. [position] is response-only: it is decided by the
 * create and reorder operations, never submitted by a client.
 */
@Serializable
internal data class ArticleCategory(
    val id: Long,
    val name: String,
    val description: String?,
    val position: Int,
    val active: Boolean,
)

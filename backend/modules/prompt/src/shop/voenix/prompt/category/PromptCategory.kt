package shop.voenix.prompt.category

import kotlinx.serialization.Serializable

/**
 * The single admin representation of a prompt category. [position] is response-only: it is decided
 * by the create, delete, and reorder operations, never submitted by a client.
 *
 * A category carries no description. The legacy schema had none either, and the storefront only
 * ever shows the name, so the field would be a column nobody reads.
 */
@Serializable
internal data class PromptCategory(
    val id: Long,
    val name: String,
    val position: Int,
    val active: Boolean,
)

package shop.voenix.production.fulfillment

import kotlinx.serialization.Serializable

/**
 * One packing line of a job, read from the snapshot the artifact was generated from.
 *
 * There is no price here and no article id: a supplier packs what the PDF prints, and what the
 * customer paid is none of the packing station's business.
 */
@Serializable
internal data class FulfillmentItemView(
    val articleName: String,
    val variantName: String,
    val supplierArticleNumber: String?,
    val quantity: Int,
)

package shop.voenix.spod

import kotlinx.serialization.Serializable

/**
 * What the partner renders of a product type: the views [SpodClient.views] answers — each with its
 * placements and a mockup per colour — and the request and answer of [SpodClient.previews], a
 * rendering of one design on one colour.
 *
 * The rule of the catalog answers holds here too: every field except an identity has a default, so
 * a sparse answer decodes into empty values the caller can judge instead of failing as a whole. A
 * missing `views` or `images` array is therefore an empty list.
 *
 * [SpodViewImage.appearanceId] and [SpodPreviewRequest.appearanceId] are strings, unlike the `Long`
 * of an order's colour: these two endpoints write the colour id as a string.
 */
@Serializable public data class SpodViews(public val views: List<SpodView> = emptyList())

/** One side of a product type ("FRONT", "BACK"), its placements, and its mockup per colour. */
@Serializable
public data class SpodView(
    public val id: String,
    public val name: String,
    public val hotspots: List<SpodHotspotName> = emptyList(),
    public val images: List<SpodViewImage> = emptyList(),
)

/** A placement as the partner names it, for example `MEDIUM_FRONT`. */
@Serializable public data class SpodHotspotName(public val name: String)

/** The mockup of one view in one colour, as a URL on the partner's image server. */
@Serializable
public data class SpodViewImage(public val appearanceId: String, public val image: String)

/**
 * One preview to render: which designs go on which placements, on which colour, and how large the
 * picture is. The partner accepts only multiples of 50 for [width] and [height].
 */
@Serializable
public data class SpodPreviewRequest(
    public val configurations: List<SpodPreviewConfiguration>,
    public val appearanceId: String,
    public val width: Int = 1000,
    public val height: Int = 1000,
)

@Serializable
public data class SpodPreviewConfiguration(public val designId: String, public val hotspot: String)

/** The rendered previews, one image per view the configured placements appear on. */
@Serializable
public data class SpodPreviews(public val images: List<SpodPreviewImage> = emptyList())

@Serializable
public data class SpodPreviewImage(
    public val url: String,
    public val viewId: String = "",
    public val viewName: String = "",
)

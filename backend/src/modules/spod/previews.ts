import { z } from 'zod'
import { lenientId } from './catalog'

/**
 * What the partner renders of a product type: the views `views` answers, each with its placements
 * and a mockup per colour, and the request and answer of `previews`, one design rendered on one
 * colour. A missing `views`, `hotspots` or `images` array reads as empty, like every catalog
 * answer. Both endpoints write the colour id as a string, unlike the number of an order's colour.
 */

const spodHotspotName = z.object({ name: z.string() })

const spodViewImage = z.object({ appearanceId: lenientId, image: z.string() })

const spodView = z.object({
  id: lenientId,
  name: z.string(),
  hotspots: z.array(spodHotspotName).default([]),
  images: z.array(spodViewImage).default([]),
})

export const spodViews = z.object({ views: z.array(spodView).default([]) })

const spodPreviewImage = z.object({
  url: z.string(),
  viewId: lenientId.default(''),
  viewName: z.string().default(''),
})

export const spodPreviews = z.object({ images: z.array(spodPreviewImage).default([]) })

/** A placement as the partner names it, for example `MEDIUM_FRONT`. */
export type SpodHotspotName = z.output<typeof spodHotspotName>
/** The mockup of one view in one colour, as a URL on the partner's image server. */
export type SpodViewImage = z.output<typeof spodViewImage>
/** One side of a product type (`FRONT`, `BACK`), its placements, and its mockup per colour. */
export type SpodView = z.output<typeof spodView>
export type SpodViews = z.output<typeof spodViews>
export type SpodPreviewImage = z.output<typeof spodPreviewImage>
/** The rendered previews, one image per view the configured placements appear on. */
export type SpodPreviews = z.output<typeof spodPreviews>

export interface SpodPreviewConfiguration {
  designId: string
  hotspot: string
}

/**
 * One preview to render: which designs go on which placements, on which colour, and how large the
 * picture is. The partner accepts only multiples of 50 for `width` and `height`.
 */
export interface SpodPreviewRequest {
  configurations: SpodPreviewConfiguration[]
  appearanceId: string
  width: number
  height: number
}

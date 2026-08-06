/**
 * The request body of every admin reorder route: move `sourceId` to the place currently held by
 * `targetId`.
 *
 * Article categories, article subcategories, and mugs all order the same way, so the backend gives
 * them one shared input instead of three identical bodies with three different field names. The
 * frontend mirrors that with this one type.
 *
 * The answer is always the complete new order as a bare array. An unknown id is `404`, and two
 * equal, missing, or non-positive ids are `400 Validation failed`.
 */
export interface ReorderRequest {
  sourceId: number
  targetId: number
}

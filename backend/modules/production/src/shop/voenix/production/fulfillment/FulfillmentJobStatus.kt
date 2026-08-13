package shop.voenix.production.fulfillment

/**
 * The two lists a fulfillment surface has: what still has to go out, and what already went.
 *
 * The status is derived from `production_jobs.shipped_at` and stored nowhere, so there is no third
 * state to strand a job in. It is *not* the generation state: an un-generated job is `OPEN` too and
 * appears in the list from the split on, marked as having no PDF yet — a job stuck on a missing
 * image must be visible, not invisible.
 */
internal enum class FulfillmentJobStatus {
    OPEN,
    SHIPPED,
}

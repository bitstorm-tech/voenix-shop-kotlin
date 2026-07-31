package shop.voenix.order

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The print images an order line points at, declared here with its identity and its stored name.
 *
 * The table belongs to the image and cart slice; the order module asks it two questions only.
 *
 * *Does the image a placement names still exist?* — because the alternative is worse. The
 * `print_image_id` foreign key would refuse the insert anyway, but `order_items` has three foreign
 * keys, so SQL state `23503` could not say *which* reference failed, and a repository must never
 * guess that from a constraint name. Asking first turns the answer into
 * [OrderWriteResult.UnknownPrintImage]; the foreign key stays the concurrency-safe authority behind
 * it, and an image deleted in the gap surfaces as an unexpected failure rather than a wrong result.
 *
 * The line's other nullable reference, `prompt_id`, deliberately gets no such pre-flight query: a
 * deleted prompt has already had its reference nulled in the customer's own cart line by that
 * table's `ON DELETE SET NULL`, so a placement practically never carries a prompt id that is gone.
 * What is left is a race of milliseconds — a prompt deleted between the checkout's read and the
 * placement's insert — and it is answered the way an unforeseen collision should be: the foreign
 * key refuses the insert, the `23503` is rethrown, and the placement fails visibly instead of
 * quietly storing a line with the wrong prompt story.
 *
 * *Under which name was it stored?* — that name is the only thing production needs to get the file
 * itself, and it is handed to `shop.voenix.image.PrivateImageStorage.originalPaths` unchanged. The
 * order module never combines it with a directory: where private originals live is the image
 * module's secret, and stays one.
 */
internal object PrintImages : LongIdTable("print_images") {
    val filename = varchar("filename", 64)
}

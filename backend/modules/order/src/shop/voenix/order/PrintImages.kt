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
 * *Under which name was it stored?* — that name is the only thing production needs to get the file
 * itself, and it is handed to `shop.voenix.image.PrivateImageStorage.originalPaths` unchanged. The
 * order module never combines it with a directory: where private originals live is the image
 * module's secret, and stays one.
 */
internal object PrintImages : LongIdTable("print_images") {
    val filename = varchar("filename", 64)
}

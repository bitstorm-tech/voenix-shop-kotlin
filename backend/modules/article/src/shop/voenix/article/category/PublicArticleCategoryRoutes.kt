package shop.voenix.article.category

import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.article.respondPublicRead

private const val BASE_PATH = "/api/articles/categories"

/**
 * The storefront navigation route.
 *
 * It sits outside the `authenticate` block of [installArticleCategoryRoutes], and outside any
 * article type: one menu covers the whole shop, which is why the path carries no type segment and
 * why the mug-only `/api/articles/mugs/categories` was removed rather than joined by a shirt twin.
 */
internal fun Application.installPublicArticleCategoryRoutes(
    categories: PublicArticleCategoryOperations
) {
    routing { get(BASE_PATH) { call.respondPublicRead(categories.list()) } }
}

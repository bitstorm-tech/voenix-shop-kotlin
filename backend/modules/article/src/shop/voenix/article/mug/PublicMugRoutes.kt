package shop.voenix.article.mug

import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.article.respondPublicRead

private const val BASE_PATH = "/api/articles/mugs"

/**
 * The storefront mug route.
 *
 * It is registered outside the `authenticate` block of [installMugArticleRoutes], which is the
 * whole point of a separate installer: a customer browsing the shop has no session, so anonymous
 * access is not a rule this handler applies but the absence of the admin subtree around it. The
 * path is `/api/articles/...` rather than `/api/admin/articles/...`, so the two trees cannot be
 * confused by a reader or by Ktor.
 *
 * `GET /api/articles/mugs/categories` used to sit next to it and is gone: with a second article
 * type the storefront navigation is one type-agnostic menu at `/api/articles/categories`.
 */
internal fun Application.installPublicMugRoutes(mugs: PublicMugOperations) {
    routing { get(BASE_PATH) { call.respondPublicRead(mugs.list()) } }
}

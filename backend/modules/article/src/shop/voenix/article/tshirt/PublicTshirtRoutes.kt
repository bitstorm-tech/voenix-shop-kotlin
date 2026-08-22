package shop.voenix.article.tshirt

import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.article.respondPublicRead

private const val BASE_PATH = "/api/articles/tshirts"

/**
 * The storefront t-shirt route.
 *
 * It is registered outside the `authenticate` block of [installTshirtArticleRoutes], which is the
 * whole point of a separate installer: a customer browsing the shop has no session, so anonymous
 * access is not a rule this handler applies but the absence of the admin subtree around it. The
 * path is `/api/articles/...` rather than `/api/admin/articles/...`, so the two trees cannot be
 * confused by a reader or by Ktor.
 *
 * There is no categories route next to it. The storefront navigation is type-agnostic and lives at
 * `/api/articles/categories`, because a menu entry a customer follows is a category — not a
 * category of one article type.
 */
internal fun Application.installPublicTshirtRoutes(tshirts: PublicTshirtOperations) {
    routing { get(BASE_PATH) { call.respondPublicRead(tshirts.list()) } }
}

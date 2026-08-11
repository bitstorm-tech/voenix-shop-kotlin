package shop.voenix

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.routing
import java.io.File

/**
 * Where the built frontend lives on disk — or nowhere.
 *
 * `frontend.distPath` is empty in development on purpose: the Vite dev server serves the frontend
 * and proxies `/api` to this backend. Only a deployment that carries the built frontend inside its
 * image (the repository-root Dockerfile) sets the key, and then the directory must actually contain
 * an `index.html` — a full-stack image without a frontend is a broken build, and refusing to start
 * is how the mistake surfaces before a customer does.
 */
internal class FrontendSettings(val distDirectory: File?) {
    companion object {
        fun from(config: ApplicationConfig): FrontendSettings {
            val configured =
                config.propertyOrNull("frontend.distPath")?.getString()?.takeIf(String::isNotBlank)
                    ?: return FrontendSettings(null)
            val directory = File(configured).absoluteFile.normalize()
            check(directory.resolve("index.html").isFile) {
                "frontend.distPath points to $directory, but there is no index.html in it"
            }
            return FrontendSettings(directory)
        }
    }
}

/** Vite content-hashes these on every build, so a browser may cache them forever. */
private const val ONE_YEAR_IN_SECONDS = 31_536_000

/** `Cache-Control` understands `immutable`, but Ktor ships no type for it. */
private val immutable =
    object : CacheControl(null) {
        override fun toString(): String = "immutable"
    }

/**
 * Serves the built frontend from [FrontendSettings.distDirectory], or nothing when the directory is
 * not configured.
 *
 * The `default` page is the SPA fallback: a URL like `/cart` or `/admin/orders` exists only inside
 * the frontend router, so a full page load there must answer with `index.html`, which boots the SPA
 * and lets it read the URL. Real API routes are unaffected — Ktor always prefers the more specific
 * `/api/...` route over this catch-all.
 *
 * The cache headers mirror the split Vite produces: `index.html` and the service worker `sw.js`
 * must be revalidated on every load (they are the two entry points a deployment replaces), while
 * everything content-hashed may be cached for a year.
 */
internal fun KtorApplication.installFrontendModule(settings: FrontendSettings) {
    val distDirectory = settings.distDirectory ?: return
    routing {
        staticFiles("/", distDirectory) {
            default("index.html")
            enableAutoHeadResponse()
            contentType { file ->
                // The 3D mug preview. Ktor's extension table has no entry for glTF binaries.
                if (file.extension == "glb") ContentType("model", "gltf-binary") else null
            }
            cacheControl { file ->
                when {
                    file.extension == "html" || file.name == "sw.js" ->
                        listOf(CacheControl.NoCache(null), CacheControl.NoStore(null))
                    file.extension in setOf("js", "css", "woff2") ->
                        listOf(
                            CacheControl.MaxAge(
                                maxAgeSeconds = ONE_YEAR_IN_SECONDS,
                                visibility = CacheControl.Visibility.Public,
                            ),
                            immutable,
                        )
                    else -> emptyList()
                }
            }
        }
    }
}

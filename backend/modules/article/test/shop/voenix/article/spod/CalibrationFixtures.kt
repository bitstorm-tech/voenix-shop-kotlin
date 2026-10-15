package shop.voenix.article.spod

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The partner's calibration answers, shaped after the staging installation (issue #260, verified
 * facts): the views of a product type with the hotspots of its front and a blank mockup per colour,
 * the hotspots the calibration design is printable on, and a preview per hotspot whose magenta box
 * the test draws itself — so the frame a run writes is known to the pixel.
 *
 * Two kinds of product type are answered. A t-shirt (any id but [TOTE_PRODUCT_TYPE_ID]) lists the
 * five front hotspots of the staging tees, of which two accept the design, and the `FULL_FRONT` box
 * is the larger one. The tote lists `FULL_FRONT` alone. With [holed], every box gets a hole in its
 * middle, so no hotspot measures cleanly and the calibration fails; with [viewsRefused], the
 * partner answers every views call with a server error; [mockupOf] is where the views answer says a
 * colour's mockup is, so a test can move one.
 *
 * The mockup downloads (`/mockups/…`) are deliberately *not* answered here: they are pictures like
 * the listing images, and a test that lets the image server fail wants them to fail too.
 */
internal class CalibrationAnswers(
    private val holed: Boolean = false,
    private val viewsRefused: Boolean = false,
    private val mockupOf: (productTypeId: Long, appearanceId: Long) -> String = ::mockupUrl,
) {
    /** Answers [request] when it is a calibration call or one of its downloads, else `null`. */
    suspend fun answer(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData? {
        val path = request.url.encodedPath
        val productTypeId = path.removePrefix("/productTypes/").substringBefore('/').toLongOrNull()
        return when {
            path == "/designs/upload" -> scope.respondJson("""{"designId":"$DESIGN_ID"}""")
            productTypeId != null && path.endsWith("/views") && viewsRefused ->
                scope.respondError(HttpStatusCode.InternalServerError)
            productTypeId != null && path.endsWith("/views") ->
                scope.respondJson(views(productTypeId, mockupOf))
            productTypeId != null && path.contains("/hotspots/design/") ->
                scope.respondJson(printableHotspots(productTypeId))
            productTypeId != null && path.endsWith("/previews") ->
                scope.respondJson(previews(productTypeId, hotspotOf(request)))
            path.startsWith("/previews/") -> scope.respondPreview(path)
            else -> null
        }
    }

    private fun MockRequestHandleScope.respondPreview(path: String): HttpResponseData {
        val productTypeId = path.removePrefix("/previews/").substringBefore('/').toLong()
        val hotspot = path.substringAfterLast('/').substringBefore(',')
        return respond(
            content = magentaBoxPng(box(productTypeId, hotspot), holed = holed),
            headers = headersOf(HttpHeaders.ContentType, "image/png"),
        )
    }
}

internal const val DESIGN_ID = "design-1"

/** The Stanley/Stella tote bag of the staging installation: one front hotspot, one size. */
internal const val TOTE_PRODUCT_TYPE_ID = 2973L

/** The two colours every fixture article has, as the views answer names them. */
private val APPEARANCE_IDS = listOf(5L, 6L)

internal fun mockupUrl(
    productTypeId: Long,
    appearanceId: Long,
): String = "https://cdn.example.test/mockups/$productTypeId/$appearanceId.png"

/** The frame the tee's larger printable hotspot measures to, in the shape the row stores. */
internal val TEE_FRAME =
    PrintFrame(leftPct = 28.5, topPct = 18.8, widthPct = 42.5, heightPct = 42.5)

internal val TOTE_FRAME =
    PrintFrame(leftPct = 33.8, topPct = 53.6, widthPct = 32.4, heightPct = 32.4)

private fun views(
    productTypeId: Long,
    mockupOf: (Long, Long) -> String,
): String {
    val front =
        if (productTypeId == TOTE_PRODUCT_TYPE_ID) {
            listOf("FULL_FRONT")
        } else {
            listOf("FULL_FRONT", "MEDIUM_FRONT", "CENTER_CHEST", "LEFT_CHEST", "RIGHT_CHEST")
        }
    val mockups = APPEARANCE_IDS.joinToString { appearanceId ->
        """{"appearanceId":"$appearanceId","image":"${mockupOf(productTypeId, appearanceId)}"}"""
    }
    return """
        {"views":[
          {"id":"1","name":"FRONT","hotspots":[${front.joinToString { """{"name":"$it"}""" }}],
           "images":[$mockups]},
          {"id":"2","name":"BACK","hotspots":[{"name":"FULL_BACK"}],"images":[]}
        ]}
        """
        .trimIndent()
}

private fun printableHotspots(productTypeId: Long): String {
    val printable =
        if (productTypeId == TOTE_PRODUCT_TYPE_ID) {
            listOf("FULL_FRONT")
        } else {
            listOf("FULL_FRONT", "MEDIUM_FRONT")
        }
    return """{"hotspots":[${printable.joinToString { """{"name":"$it"}""" }}]}"""
}

private fun previews(
    productTypeId: Long,
    hotspot: String,
): String =
    """
    {"images":[
      {"url":"https://cdn.example.test/previews/$productTypeId/$hotspot","viewId":"1","viewName":"FRONT"},
      {"url":"https://cdn.example.test/previews/$productTypeId/back","viewId":"2","viewName":"BACK"}
    ]}
    """
        .trimIndent()

private suspend fun hotspotOf(request: HttpRequestData): String =
    Json.parseToJsonElement(request.body.toByteArray().decodeToString())
        .jsonObject
        .getValue("configurations")
        .jsonArray
        .single()
        .jsonObject
        .getValue("hotspot")
        .jsonPrimitive
        .content

/** Where the design lands on a 1000 × 1000 preview, per product type and hotspot. */
private fun box(
    productTypeId: Long,
    hotspot: String,
): Box =
    when {
        productTypeId == TOTE_PRODUCT_TYPE_ID -> Box(338, 536, 324, 324)
        hotspot == "FULL_FRONT" -> Box(285, 188, 425, 425)
        else -> Box(356, 189, 284, 284)
    }

/** A rectangle of pixels: its top-left corner and its size. */
internal data class Box(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * A preview as the partner renders the calibration design: an opaque magenta [box] on a grey
 * garment, [edge] pixels square. With [holed], a 100 × 100 patch of garment is left in the middle
 * of the box; with [antiAliased], a one-pixel translucent rim is drawn around it.
 */
internal fun magentaBoxPng(
    box: Box,
    edge: Int = PREVIEW_EDGE,
    holed: Boolean = false,
    antiAliased: Boolean = false,
): ByteArray {
    val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = GARMENT
        graphics.fillRect(0, 0, edge, edge)
        if (antiAliased) {
            graphics.color = Color(255, 0, 255, 128)
            graphics.fillRect(box.x - 1, box.y - 1, box.width + 2, box.height + 2)
        }
        graphics.color = Color.MAGENTA
        graphics.fillRect(box.x, box.y, box.width, box.height)
        if (holed) {
            graphics.color = GARMENT
            graphics.fillRect(
                box.x + box.width / 2 - HOLE_EDGE / 2,
                box.y + box.height / 2 - HOLE_EDGE / 2,
                HOLE_EDGE,
                HOLE_EDGE,
            )
        }
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { stream ->
        ImageIO.write(image, "png", stream)
        stream.toByteArray()
    }
}

internal const val PREVIEW_EDGE = 1000
private const val HOLE_EDGE = 100
private val GARMENT = Color(200, 200, 200)

/** One stored mockup of an article: its colour by the partner's id, its file, and its source. */
internal data class StoredMockup(
    val spodAppearanceId: Long,
    val filename: String,
    val sourceUrl: String?,
)

internal fun mockups(
    dataSource: DataSource,
    articleId: Long,
): List<StoredMockup> =
    query(
        dataSource,
        """
        SELECT a.spod_appearance_id, i.filename, i.source_url
        FROM voenix.article_spod_images i
        JOIN voenix.article_spod_appearances a ON a.id = i.appearance_id
        WHERE a.article_id = $articleId AND i.role = 'MOCKUP'
        ORDER BY a.position
        """
            .trimIndent(),
    ) { rows ->
        StoredMockup(
            spodAppearanceId = rows.getLong("spod_appearance_id"),
            filename = rows.getString("filename"),
            sourceUrl = rows.getString("source_url"),
        )
    }

/** The frame and hotspot of a stored article, as the row holds them. */
internal fun storedFrame(dataSource: DataSource, articleId: Long): StoredFrame {
    val article = storedArticle(dataSource, articleId)
    return StoredFrame(
        hotspot = article.getString("spod_hotspot"),
        left = article.getString("print_frame_left_pct"),
        top = article.getString("print_frame_top_pct"),
        width = article.getString("print_frame_width_pct"),
        height = article.getString("print_frame_height_pct"),
    )
}

internal data class StoredFrame(
    val hotspot: String?,
    val left: String?,
    val top: String?,
    val width: String?,
    val height: String?,
)

internal val TEE_STORED_FRAME = StoredFrame("FULL_FRONT", "28.50", "18.80", "42.50", "42.50")
internal val NO_STORED_FRAME = StoredFrame(null, null, null, null, null)

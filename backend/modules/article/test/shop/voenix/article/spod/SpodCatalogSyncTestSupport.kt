package shop.voenix.article.spod

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.SyncedSpodArticles
import shop.voenix.article.persistence.ArticleSpodSyncRepository
import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodCatalogArticle
import shop.voenix.spod.SpodCatalogImage
import shop.voenix.spod.SpodCatalogPage
import shop.voenix.spod.SpodCatalogVariant
import shop.voenix.spod.SpodClient
import shop.voenix.spod.SpodEnvironment

/**
 * The fixture of one run: the service under test, the image storage it stores into, and every path
 * the client asked the partner for.
 */
internal class SyncFixture(
    val sync: SpodCatalogSync,
    val storage: RecordingPublicImageStorage,
    val hits: MutableList<String>,
) {
    suspend fun run(): SpodSyncReport =
        assertIs<SpodSyncResult.Reported>(sync.sync(source())).report
}

/**
 * Builds the fixtures of one test. Every fixture mints from its own stretch of the file-name list,
 * so a name says which run stored it; the counter lives per test instance, so the first fixture of
 * every test starts at [filename] `0`.
 */
internal class SyncFixtures {
    private var mintedFilenames = 0

    fun of(
        dataSource: DataSource,
        page: String,
        sizeChartUrl: String = SIZE_CHART_URL,
    ): SyncFixture =
        of(dataSource) { request ->
            when {
                request.url.encodedPath == "/articles" -> respondJson(page)
                request.url.encodedPath.endsWith("/size-chart") ->
                    respondJson("""{"sizeImageUrl":"$sizeChartUrl"}""")
                else -> respondImage()
            }
        }

    fun of(
        dataSource: DataSource,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SyncFixture {
        val hits = mutableListOf<String>()
        val storage =
            RecordingPublicImageStorage(
                List(FILENAMES_PER_FIXTURE) { index -> filename(mintedFilenames + index) }
            )
        mintedFilenames += FILENAMES_PER_FIXTURE
        val engine = MockEngine { request ->
            hits += request.url.encodedPath
            answer(request)
        }
        val service =
            SpodCatalogSyncService(
                ArticleSpodSyncRepository(Database.connect(datasource = dataSource)),
                SpodClient(engine = engine, nowMillis = { 0 }, pause = {}),
                storage,
            )
        return SyncFixture(service, storage, hits)
    }
}

internal const val SUPPLIER_ID = 1L
internal const val DESTINATION_ID = 1L
internal const val PRODUCT_TYPE_ID = 812L
internal const val PRODUCT_TYPE_NAME = "Men's T-Shirt"

/** The Spreadshirt product every image of these tests renders; its URLs name it as `lookupId`. */
internal const val PRODUCT_ID = 598279462L
internal const val SIZE_CHART_URL = "https://cdn.example.test/chart-1.png"
internal const val SIZE_CHART_ANSWER = """{"sizeImageUrl":"$SIZE_CHART_URL"}"""

/** The chart of the same product type after the partner moved it to another file. */
internal const val MOVED_SIZE_CHART_URL = "https://cdn.example.test/chart-2.png"

/** More names than any single run of these tests stores, so no fixture runs out of them. */
internal const val FILENAMES_PER_FIXTURE = 8

internal fun filename(index: Int): String = "%08d-0000-4000-8000-000000000000.webp".format(index)

internal fun source(): SpodCatalogSource =
    SpodCatalogSource(
        supplierId = SUPPLIER_ID,
        access =
            SpodAccess(
                destinationId = DESTINATION_ID,
                environment = SpodEnvironment.PRODUCTION,
                accessToken = "sync-token",
                timeoutSeconds = 30,
            ),
    )

internal fun seed(dataSource: DataSource) {
    ArticleTestSchema.reset(dataSource)
    ArticleTestSchema.seedSuppliers(dataSource, "Shirt supplier")
    ArticleTestSchema.seedCategories(dataSource, "Shirts")
    SyncedSpodArticles.seedSpodDestination(
        dataSource,
        id = DESTINATION_ID,
        supplierId = SUPPLIER_ID,
    )
}

/**
 * Everything an admin decides about an article, decided: it is on sale, in a category, with a price
 * and a default variant that is not the first one. Being on sale needs a measured frame, which no
 * run of T1 measures, so the frame is set here the way a measuring run will set it.
 */
internal fun makeShopOwnedDecisions(dataSource: DataSource) {
    ArticleTestSchema.seedVat(dataSource)
    ArticleTestSchema.execute(
        dataSource,
        """
        INSERT INTO voenix.prices (
            id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
            purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
            sales_vat_id, sales_calculation_mode, sales_active_row,
            sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
        ) VALUES (1, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000);
        UPDATE voenix.article_spod_articles SET
            active = TRUE, category_id = 1, price_id = 1,
            print_frame_left_pct = 11.00, print_frame_top_pct = 12.00,
            print_frame_width_pct = 13.00, print_frame_height_pct = 14.00,
            spod_hotspot = 'MEDIUM_FRONT'
        WHERE id = 1;
        UPDATE voenix.article_spod_variants SET is_default = FALSE WHERE id = 1;
        UPDATE voenix.article_spod_variants SET is_default = TRUE WHERE id = 3;
        """
            .trimIndent(),
    )
}

internal fun catalog(vararg articles: SpodCatalogArticle): String =
    catalog(articles.toList(), count = articles.size)

/** One listing page, with a [count] a test may state differently from what the page carries. */
internal fun catalog(
    articles: List<SpodCatalogArticle>,
    count: Int,
): String =
    Json.encodeToString(
        SpodCatalogPage.serializer(),
        SpodCatalogPage(items = articles, count = count),
    )

internal fun article(
    id: String = "a-1",
    title: String = "Classic Shirt",
    description: String = "A shirt",
    variants: List<SpodCatalogVariant>,
    images: List<SpodCatalogImage>,
): SpodCatalogArticle =
    SpodCatalogArticle(
        id = id,
        title = title,
        description = description,
        variants = variants,
        images = images,
    )

/** Two colours in two sizes; the black one with two listing images, the white one with one. */
internal fun twoColourShirt(
    id: String = "a-1",
    title: String = "Classic Shirt",
    description: String = "A shirt",
): SpodCatalogArticle =
    article(
        id = id,
        title = title,
        description = description,
        variants =
            listOf(
                variant("$id-v-1", appearanceId = 5, sizeId = 91, sizeName = "M"),
                variant("$id-v-2", appearanceId = 5, sizeId = 92, sizeName = "L"),
                variant(
                    "$id-v-3",
                    appearanceId = 6,
                    sizeId = 91,
                    sizeName = "M",
                    appearanceName = "White",
                    colorValue = "#FFFFFF",
                ),
                variant(
                    "$id-v-4",
                    appearanceId = 6,
                    sizeId = 92,
                    sizeName = "L",
                    appearanceName = "White",
                    colorValue = "#FFFFFF",
                ),
            ),
        images =
            listOf(
                image("$id-i-1", appearanceId = 5, perspective = "back"),
                image("$id-i-2", appearanceId = 5, perspective = "FRONT"),
                image("$id-i-3", appearanceId = 6, perspective = "front_top"),
            ),
    )

/**
 * Deletes one synced article the way the admin API does: its variants, its rows (the colours and
 * their images cascade), its identities.
 */
internal fun deleteArticle(
    dataSource: DataSource,
    articleId: Long,
) {
    ArticleTestSchema.execute(
        dataSource,
        """
        DELETE FROM voenix.article_spod_variants WHERE article_id = $articleId;
        DELETE FROM voenix.article_variant_identities WHERE article_id = $articleId;
        DELETE FROM voenix.article_spod_articles WHERE id = $articleId;
        DELETE FROM voenix.article_identities WHERE id = $articleId;
        """
            .trimIndent(),
    )
}

/** The same article after the merchant switched the white colour off. */
internal fun blackOnlyShirt(): SpodCatalogArticle =
    article(
        variants =
            listOf(
                variant("a-1-v-1", appearanceId = 5, sizeId = 91, sizeName = "M"),
                variant("a-1-v-2", appearanceId = 5, sizeId = 92, sizeName = "L"),
            ),
        images =
            listOf(
                image("a-1-i-1", appearanceId = 5, perspective = "back"),
                image("a-1-i-2", appearanceId = 5, perspective = "FRONT"),
            ),
    )

internal fun variant(
    id: String,
    appearanceId: Long,
    sizeId: Long = 91,
    sizeName: String = "M",
    appearanceName: String = "Black",
    colorValue: String? = "#101010",
    productTypeId: Long = PRODUCT_TYPE_ID,
    productTypeName: String = PRODUCT_TYPE_NAME,
): SpodCatalogVariant =
    SpodCatalogVariant(
        id = id,
        productTypeId = productTypeId,
        productTypeName = productTypeName,
        appearanceId = appearanceId,
        appearanceName = appearanceName,
        appearanceColorValue = colorValue,
        sizeId = sizeId,
        sizeName = sizeName,
        sku = "SKU-$id",
    )

internal fun image(
    id: String,
    appearanceId: Long,
    perspective: String? = "front",
): SpodCatalogImage =
    SpodCatalogImage(
        id = id,
        productId = PRODUCT_ID,
        appearanceId = appearanceId,
        perspective = perspective,
        imageUrl =
            "https://cdn.example.test/image-server/v1/products/lookupId/views/1," +
                "appearanceId=$appearanceId,mediaType=png/$id.png",
    )

internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))

internal fun MockRequestHandleScope.respondImage(): HttpResponseData =
    respond(
        content = byteArrayOf(1, 2, 3, 4),
        headers = headersOf(HttpHeaders.ContentType, "image/png"),
    )

/** One stored variant, in the shape every assertion about the variant matrix is written in. */
internal data class StoredVariant(
    val colorName: String,
    val colorHex: String,
    val sizeLabel: String,
    val active: Boolean,
    val isDefault: Boolean,
)

internal fun variantRow(
    colorName: String,
    colorHex: String,
    sizeLabel: String,
    active: Boolean,
    isDefault: Boolean,
): StoredVariant = StoredVariant(colorName, colorHex, sizeLabel, active, isDefault)

/**
 * The four variants of [twoColourShirt] as the shop stores them: Black M (the default) and L, then
 * White M and L, each colour with its own `active`.
 */
internal fun classicShirtRows(
    blackActive: Boolean,
    whiteActive: Boolean,
): List<StoredVariant> =
    listOf(
        variantRow("Black", "#101010", "M", active = blackActive, isDefault = true),
        variantRow("Black", "#101010", "L", active = blackActive, isDefault = false),
        variantRow("White", "#ffffff", "M", active = whiteActive, isDefault = false),
        variantRow("White", "#ffffff", "L", active = whiteActive, isDefault = false),
    )

internal fun variants(
    dataSource: DataSource,
    articleId: Long,
): List<StoredVariant> =
    query(
        dataSource,
        """
        SELECT a.color_name, a.color_hex, v.size_label, v.active, v.is_default
        FROM voenix.article_spod_variants v
        JOIN voenix.article_spod_appearances a ON a.id = v.appearance_id
        WHERE v.article_id = $articleId
        ORDER BY v.id
        """
            .trimIndent(),
    ) { rows ->
        StoredVariant(
            colorName = rows.getString("color_name"),
            colorHex = rows.getString("color_hex"),
            sizeLabel = rows.getString("size_label"),
            active = rows.getBoolean("active"),
            isDefault = rows.getBoolean("is_default"),
        )
    }

/** One stored article as a map of column to value, so an assertion names the column it means. */
internal fun storedArticle(
    dataSource: DataSource,
    articleId: Long,
): StoredArticle =
    StoredArticle(
        query(dataSource, "SELECT * FROM voenix.article_spod_articles WHERE id = $articleId") { rows
                ->
                (1..rows.metaData.columnCount).associate { index ->
                    rows.metaData.getColumnLabel(index) to rows.getObject(index)
                }
            }
            .single()
    )

internal class StoredArticle(private val columns: Map<String, Any?>) {
    fun getObject(column: String): Any? = columns[column]

    fun getString(column: String): String? = columns[column]?.toString()

    fun getInt(column: String): Int = (columns[column] as Number).toInt()

    fun getLong(column: String): Long = (columns[column] as Number).toLong()

    fun getBoolean(column: String): Boolean = columns[column] as Boolean

    fun getInstant(column: String): Instant = (columns[column] as Timestamp).toInstant()
}

/** One stored colour of an article, by the partner's appearance id. */
internal data class StoredAppearance(
    val spodAppearanceId: Long,
    val colorName: String,
    val colorHex: String,
    val position: Int,
)

internal fun appearances(
    dataSource: DataSource,
    articleId: Long,
): List<StoredAppearance> =
    query(
        dataSource,
        """
        SELECT spod_appearance_id, color_name, color_hex, position
        FROM voenix.article_spod_appearances
        WHERE article_id = $articleId
        ORDER BY position
        """
            .trimIndent(),
    ) { rows ->
        StoredAppearance(
            spodAppearanceId = rows.getLong("spod_appearance_id"),
            colorName = rows.getString("color_name"),
            colorHex = rows.getString("color_hex"),
            position = rows.getInt("position"),
        )
    }

/** One stored listing image: its colour by the partner's id, its place, and its two names. */
internal data class StoredListingImage(
    val spodAppearanceId: Long,
    val position: Int,
    val spodImageId: String,
    val filename: String,
)

/** The listing images of one article, colour by colour in the partner's order. */
internal fun listingImages(
    dataSource: DataSource,
    articleId: Long,
): List<StoredListingImage> =
    query(
        dataSource,
        """
        SELECT a.spod_appearance_id, i.position, i.spod_image_id, i.filename
        FROM voenix.article_spod_images i
        JOIN voenix.article_spod_appearances a ON a.id = i.appearance_id
        WHERE a.article_id = $articleId AND i.role = 'LISTING'
        ORDER BY a.position, i.position
        """
            .trimIndent(),
    ) { rows ->
        StoredListingImage(
            spodAppearanceId = rows.getLong("spod_appearance_id"),
            position = rows.getInt("position"),
            spodImageId = rows.getString("spod_image_id"),
            filename = rows.getString("filename"),
        )
    }

internal fun <T> query(
    dataSource: DataSource,
    sql: String,
    row: (ResultSet) -> T,
): List<T> =
    dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(row(rows))
                    }
                }
            }
        }
    }

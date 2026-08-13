package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.Table

/**
 * The item lines one production job's artifact was rendered from.
 *
 * The rows are written in the very transaction that records `generated_at` and `content_sha256`, so
 * they describe exactly the PDF that exists on disk and never change afterwards. That is what lets
 * the supplier page show the content of the immutable document instead of today's master data: a
 * later supplier reassignment or an article rename moves no row here.
 *
 * [position] is the 1-based place of the line inside the supplier's share of the order, which is
 * also the primary key together with the job — items are parts of the job, not rows with a life
 * cycle of their own.
 */
internal object ProductionJobItems : Table("production_job_items") {
    val productionJobId = long("production_job_id")
    val position = integer("position")
    val articleName = varchar("article_name", 255)
    val variantName = varchar("variant_name", 255)
    val supplierArticleNumber = varchar("supplier_article_number", 255).nullable()
    val quantity = integer("quantity")

    override val primaryKey: PrimaryKey = PrimaryKey(productionJobId, position)
}

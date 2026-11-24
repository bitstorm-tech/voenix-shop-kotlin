import { sql, type Kysely, type UpdateObject } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import { createLogger, errorDetails } from '@/platform/logging'
import { type OrderLoader, type ProductionItem } from '../orders'
import type { ArtifactStore } from '../pdf/artifact-store'
import { renderProductionPdf } from '../pdf/renderer'
import '../schema'

const logger = createLogger('production-generation')

interface SnapshotItem {
  position: number
  articleName: string
  variantName: string
  supplierArticleNumber: string | null
  quantity: number
}

const normalizedSupplierArticleNumber = (value: string | null) =>
  value?.trim() === '' ? null : value

/**
 * Verifies that the live lines routed to this supplier still match the exact set snapshotted in
 * `production_job_items` at split time. Reassigning an article to another supplier between split
 * and generation would otherwise silently drop lines from the rendered PDF.
 */
function matchesJobSnapshot(share: readonly ProductionItem[], snapshot: readonly SnapshotItem[]) {
  if (share.length === 0 || share.length !== snapshot.length) return false
  return share.every((item, index) => {
    const snap = snapshot[index]
    return (
      snap !== undefined &&
      snap.position === index + 1 &&
      snap.articleName === item.articleName &&
      snap.variantName === item.variantName &&
      snap.supplierArticleNumber === normalizedSupplierArticleNumber(item.supplierArticleNumber) &&
      snap.quantity === item.quantity
    )
  })
}

/**
 * The worker stage that turns every open SFTP job into its immutable artifact exactly once: verify
 * the live lines against `production_job_items`, render the supplier's PDF, write it under the
 * artifact root (temporary file plus atomic rename), then record the digest, `generated_at`, and
 * `prepared_at`. A crash between the file and the commit leaves an open job whose next attempt
 * replaces the file, so the recorded digest always describes the bytes under the final path. A
 * SPOD job never reaches this stage (SPOD fulfillment decision 2).
 */
export function createGenerationStage({
  db,
  loadOrder,
  artifacts,
}: {
  db: Kysely<Tables>
  loadOrder: OrderLoader
  artifacts: ArtifactStore
}) {
  /** Touches the job only while its artifact does not exist; answers whether it did. */
  const updateOpenJob = async (jobId: number, values: UpdateObject<Tables, 'production_jobs'>) => {
    const result = await db
      .updateTable('production_jobs')
      .set(values)
      .where('id', '=', jobId)
      .where('generated_at', 'is', null)
      .executeTakeFirst()
    return result.numUpdatedRows > 0n
  }
  const recordFailure = (jobId: number, code: string) =>
    updateOpenJob(jobId, { last_generation_error_code: code })

  const jobItems = (jobId: number) =>
    read(db, (trx) =>
      trx
        .selectFrom('production_job_items')
        .select([
          'position',
          'article_name as articleName',
          'variant_name as variantName',
          'supplier_article_number as supplierArticleNumber',
          'quantity',
        ])
        .where('production_job_id', '=', jobId)
        .orderBy('position')
        .execute(),
    )

  return async (signal?: AbortSignal): Promise<void> => {
    const jobs = await read(db, (trx) =>
      trx
        .selectFrom('production_jobs as j')
        .innerJoin('production_requests as r', 'r.id', 'j.request_id')
        .select([
          'j.id',
          'r.order_id as orderId',
          'j.supplier_id as supplierId',
          'j.file_name as fileName',
          'j.generation_attempt_count as attemptCount',
        ])
        .where('j.generated_at', 'is', null)
        .where('j.fulfillment_channel', '=', 'SFTP')
        .orderBy('j.id')
        .execute(),
    )
    for (const job of jobs) {
      if (signal?.aborted === true) return
      const started = await updateOpenJob(job.id, {
        generation_attempt_count: sql`generation_attempt_count + 1`,
      })
      if (!started) continue
      const loaded = await loadOrder(job.orderId)
      if (loaded.kind === 'failed') {
        await recordFailure(job.id, loaded.code)
        continue
      }
      const snapshot = await jobItems(job.id)
      const share = loaded.order.items.filter((item) => item.supplierId === job.supplierId)
      if (!matchesJobSnapshot(share, snapshot)) {
        logger.warn(
          { jobId: job.id, orderId: job.orderId, supplierId: job.supplierId },
          'Production job items no longer match the split snapshot; refusing PDF generation',
        )
        await recordFailure(job.id, 'ITEM_SET_CHANGED')
        continue
      }
      const rendered = await renderProductionPdf(loaded.order, job.supplierId)
      if (rendered.kind === 'failed') {
        await recordFailure(job.id, rendered.error)
        continue
      }
      try {
        await artifacts.write(job.id, job.fileName, rendered.pdf.bytes)
      } catch (error) {
        logger.error({ jobId: job.id, error: errorDetails(error) }, 'Artifact write failed')
        await recordFailure(job.id, 'ARTIFACT_WRITE_FAILED')
        continue
      }
      // For this channel "the document exists" and "the job may be shipped" are one moment.
      await updateOpenJob(job.id, {
        content_sha256: rendered.pdf.sha256,
        generated_at: sql<Date>`now()`,
        prepared_at: sql<Date>`now()`,
        last_generation_error_code: null,
      })
      logger.info({ jobId: job.id, attempt: job.attemptCount + 1 }, 'Production artifact generated')
    }
  }
}

import { sql, type Kysely, type UpdateObject } from 'kysely'
import { renderProducerPdfNotification, type EmailModule } from '@/modules/email'
import { read, write, type Tables } from '@/platform/db/database'
import { createLogger, errorClass } from '@/platform/logging'
import { type ProductionOrders } from '../orders'
import type { ArtifactStore } from '../pdf/artifact-store'
import { deliverOverSftp, type SftpDestination } from './sftp'
import '../schema'

const logger = createLogger('production-delivery')

/** What the stage reads of a delivery's destination: the SFTP account and the notification. */
interface DeliveryDestination extends SftpDestination {
  enabled: boolean
  label: string
  notificationEmail: string | null
  notificationName: string | null
}

/**
 * The worker stage that pushes every generated artifact to its destinations, one attempt per open
 * delivery per scan, in id order. `delivered_at` is set only after the server accepted the file,
 * and in the same transaction the producer notification is rendered and enqueued when the
 * destination names an address: delivered and notified are one commit. Every failure keeps the row
 * open with a bounded code, and one destination's failure never blocks a sibling.
 */
export function createDeliveryStage({
  db,
  artifacts,
  outbox,
  orders,
}: {
  db: Kysely<Tables>
  artifacts: ArtifactStore
  outbox: Pick<EmailModule['outbox'], 'enqueue'>
  orders: Pick<ProductionOrders, 'fulfillmentOrders'>
}) {
  const updateOpen = async (
    executor: Kysely<Tables>,
    deliveryId: number,
    values: UpdateObject<Tables, 'production_deliveries'>,
  ) => {
    const result = await executor
      .updateTable('production_deliveries')
      .set(values)
      .where('id', '=', deliveryId)
      .where('delivered_at', 'is', null)
      .executeTakeFirst()
    return result.numUpdatedRows > 0n
  }
  const recordFailure = (deliveryId: number, code: string) =>
    updateOpen(db, deliveryId, { last_error_code: code })

  /** The destination with its password, or `undefined` when the row or its detail row is gone. */
  const destination = (destinationId: number): Promise<DeliveryDestination | undefined> =>
    read(db, async (trx) => {
      const row = await trx
        .selectFrom('production_destinations as d')
        .leftJoin('production_destination_sftp as f', 'f.id', 'd.id')
        .select([
          'd.channel',
          'd.enabled',
          'd.label',
          'd.notification_email',
          'd.notification_name',
          'f.id as sftpId',
          'f.host',
          'f.port',
          'f.username',
          'f.password',
          'f.host_key_fingerprint',
          'f.remote_path',
          'f.timeout_seconds',
        ])
        .where('d.id', '=', destinationId)
        .executeTakeFirst()
      if (row === undefined) return undefined
      // Deliveries exist for SFTP jobs only and a channel never changes: anything else is a
      // broken database, not a case to handle.
      if (row.channel !== 'SFTP') {
        throw new Error(`Delivery destination ${String(destinationId)} is not an SFTP destination`)
      }
      if (
        row.sftpId === null ||
        row.host === null ||
        row.port === null ||
        row.username === null ||
        row.password === null ||
        row.host_key_fingerprint === null ||
        row.remote_path === null ||
        row.timeout_seconds === null
      ) {
        return undefined
      }
      return {
        id: destinationId,
        enabled: row.enabled,
        label: row.label,
        notificationEmail: row.notification_email,
        notificationName: row.notification_name,
        host: row.host,
        port: row.port,
        username: row.username,
        password: row.password,
        hostKeyFingerprint: row.host_key_fingerprint,
        remotePath: row.remote_path,
        timeoutSeconds: row.timeout_seconds,
      }
    })

  /**
   * Closes the delivery and, when the destination names an address, enqueues the producer
   * notification in the same commit. The guard on `delivered_at IS NULL` makes it at most one mail
   * per delivery; a failing enqueue rolls `delivered_at` back and the delivery stays open.
   */
  const complete = (
    delivery: { id: number; jobId: number; orderId: number; fileName: string },
    target: DeliveryDestination,
    orderDate: string | null,
  ) =>
    write(db, async (trx) => {
      const closed = await updateOpen(trx, delivery.id, {
        delivered_at: sql<Date>`now()`,
        last_error_code: null,
      })
      if (!closed || target.notificationEmail === null || orderDate === null) return
      const { items } = await trx
        .selectFrom('production_job_items')
        .select((eb) => eb.fn.sum<string>('quantity').as('items'))
        .where('production_job_id', '=', delivery.jobId)
        .executeTakeFirstOrThrow()
      await outbox.enqueue(
        trx,
        renderProducerPdfNotification({
          recipient: target.notificationEmail,
          orderId: delivery.orderId,
          fileName: delivery.fileName,
          destinationLabel: target.label,
          orderDate,
          itemCount: Number(items),
          producerName: target.notificationName,
        }),
        { orderId: delivery.orderId },
      )
    })

  return async (signal?: AbortSignal): Promise<void> => {
    const deliveries = await read(db, (trx) =>
      trx
        .selectFrom('production_deliveries as v')
        .innerJoin('production_jobs as j', 'j.id', 'v.production_job_id')
        .innerJoin('production_requests as r', 'r.id', 'j.request_id')
        .select([
          'v.id',
          'v.destination_id as destinationId',
          'v.attempt_count as attemptCount',
          'j.id as jobId',
          'j.file_name as fileName',
          'j.content_sha256 as contentSha256',
          'r.order_id as orderId',
        ])
        .where('v.delivered_at', 'is', null)
        .where('j.generated_at', 'is not', null)
        .orderBy('v.id')
        .execute(),
    )
    for (const delivery of deliveries) {
      if (signal?.aborted === true) return
      const started = await updateOpen(db, delivery.id, { attempt_count: sql`attempt_count + 1` })
      if (!started) continue
      const target = await destination(delivery.destinationId)
      if (target === undefined) {
        await recordFailure(delivery.id, 'DESTINATION_MISSING')
        continue
      }
      if (!target.enabled) {
        await recordFailure(delivery.id, 'DESTINATION_DISABLED')
        continue
      }
      const artifact = await artifacts.load(
        delivery.jobId,
        delivery.fileName,
        delivery.contentSha256 ?? '',
      )
      if (artifact.kind !== 'loaded') {
        await recordFailure(
          delivery.id,
          artifact.kind === 'missing' ? 'ARTIFACT_MISSING' : 'ARTIFACT_DIGEST_MISMATCH',
        )
        continue
      }
      let outcome: string | null
      try {
        outcome = await deliverOverSftp(target, delivery.fileName, artifact.bytes)
      } catch (error) {
        logger.error(
          { deliveryId: delivery.id, errorClass: errorClass(error) },
          'Production delivery failed unexpectedly',
        )
        outcome = 'DELIVERY_FAILED'
      }
      if (outcome !== null) {
        await recordFailure(delivery.id, outcome)
        continue
      }
      const orderDate =
        target.notificationEmail === null
          ? null
          : ((await orders.fulfillmentOrders([delivery.orderId])).get(delivery.orderId)
              ?.orderDate ?? null)
      if (target.notificationEmail !== null && orderDate === null) {
        // The upload stands (it is at least once anyway); the next scan repeats it with the mail.
        await recordFailure(delivery.id, 'SOURCE_NOT_FOUND')
        continue
      }
      await complete(delivery, target, orderDate)
      logger.info(
        {
          deliveryId: delivery.id,
          destinationId: target.id,
          attempt: delivery.attemptCount + 1,
        },
        'Production delivery accepted',
      )
    }
  }
}

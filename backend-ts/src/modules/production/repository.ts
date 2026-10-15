import { sql, type Kysely, type Transaction } from 'kysely'
import type { SpodCatalogSource } from '@/modules/article'
import { spodEnvironmentOfStoredValue, type SpodEnvironment } from '@/modules/spod'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import './schema'

/** The channel-shaped half of a destination, secret excluded: the same shape in and out. */
export type DestinationDetail =
  | {
      channel: 'SFTP'
      host: string
      port: number
      username: string
      hostKeyFingerprint: string
      remotePath: string
      timeoutSeconds: number
    }
  | { channel: 'SPOD'; environment: SpodEnvironment; timeoutSeconds: number }

/**
 * A destination's stored values without its secret. The secret travels only as a separate argument
 * of `insert` and `update`, so no value of this module that could be printed or answered ever
 * holds one.
 */
export interface DestinationWrite {
  supplierId: number
  label: string
  enabled: boolean
  notificationEmail: string | null
  notificationName: string | null
  detail: DestinationDetail
}

export interface StoredDestination extends DestinationWrite {
  id: number
}

export type DestinationWriteResult =
  | StoredDestination
  | 'notFound'
  | 'supplierNotFound'
  | 'enabledSpodExists'
  | 'channelImmutable'
  | 'supplierImmutable'

export type DestinationRepository = ReturnType<typeof createDestinationRepository>

/**
 * Destination persistence across the base table and the two per-channel detail tables (SPOD
 * fulfillment decision 3). Both rows are written in one transaction; the composite foreign key
 * `(id, channel)` refuses a detail row of the wrong channel. Reads never select a secret column.
 */
export function createDestinationRepository(db: Kysely<Tables>) {
  const find = (trx: Transaction<Tables>, id?: number) => {
    let query = trx
      .selectFrom('production_destinations as d')
      .leftJoin('production_destination_sftp as f', 'f.id', 'd.id')
      .leftJoin('production_destination_spod as s', 's.id', 'd.id')
      .select([
        'd.id',
        'd.supplier_id',
        'd.channel',
        'd.label',
        'd.enabled',
        'd.notification_email',
        'd.notification_name',
        'f.host',
        'f.port',
        'f.username',
        'f.host_key_fingerprint',
        'f.remote_path',
        'f.timeout_seconds as sftp_timeout_seconds',
        's.environment',
        's.timeout_seconds as spod_timeout_seconds',
      ])
    if (id !== undefined) query = query.where('d.id', '=', id)
    return query.orderBy('d.supplier_id').orderBy('d.id').execute()
  }

  const findOne = async (trx: Transaction<Tables>, id: number) => {
    const [row] = await find(trx, id)
    return row === undefined ? undefined : toStored(row)
  }

  return {
    list: () => read(db, async (trx) => (await find(trx)).map(toStored)),

    find: (id: number) => read(db, (trx) => findOne(trx, id)),

    /** Whether any SPOD destination exists, enabled or not: the startup check reads it. */
    hasSpodDestination: async (): Promise<boolean> => {
      const row = await read(db, (trx) =>
        trx
          .selectFrom('production_destinations')
          .select('id')
          .where('channel', '=', 'SPOD')
          .limit(1)
          .executeTakeFirst(),
      )
      return row !== undefined
    },

    /**
     * What a SPOD catalog sync of this destination reads with, access token included. `enabled` is
     * not part of it: a disabled destination may still sync. A destination of another channel has
     * no catalog.
     */
    catalogSource: async (id: number): Promise<SpodCatalogSource | 'notFound' | 'notSyncable'> => {
      const row = await read(db, (trx) =>
        trx
          .selectFrom('production_destinations as d')
          .leftJoin('production_destination_spod as s', 's.id', 'd.id')
          .select([
            'd.supplier_id',
            'd.channel',
            's.environment',
            's.access_token',
            's.timeout_seconds',
          ])
          .where('d.id', '=', id)
          .executeTakeFirst(),
      )
      if (row === undefined) return 'notFound'
      if (row.channel !== 'SPOD') return 'notSyncable'
      return {
        supplierId: row.supplier_id,
        access: {
          destinationId: id,
          environment: spodEnvironmentOfStoredValue(detailValue(row.environment)),
          accessToken: detailValue(row.access_token),
          timeoutSeconds: detailValue(row.timeout_seconds),
        },
      }
    },

    insert: (destination: DestinationWrite, secret: string) =>
      executePostgresWrite(
        { uniqueViolation: 'enabledSpodExists', foreignKeyViolation: 'supplierNotFound' },
        () =>
          write(db, async (trx) => {
            const { id } = await trx
              .insertInto('production_destinations')
              .values(baseValues(destination))
              .returning('id')
              .executeTakeFirstOrThrow()
            await insertDetail(trx, id, destination.detail, secret)
            return { id, ...destination }
          }),
      ),

    /**
     * Replaces a destination. The channel and the supplier are fixed at creation; both are checked
     * before anything is written. A `null` secret keeps the stored one.
     */
    update: (id: number, destination: DestinationWrite, newSecret: string | null) =>
      executePostgresWrite(
        { uniqueViolation: 'enabledSpodExists', foreignKeyViolation: 'supplierNotFound' },
        () =>
          write(db, async (trx): Promise<DestinationWriteResult> => {
            const stored = await findOne(trx, id)
            if (stored === undefined) return 'notFound'
            if (stored.detail.channel !== destination.detail.channel) return 'channelImmutable'
            if (stored.supplierId !== destination.supplierId) return 'supplierImmutable'
            await trx
              .updateTable('production_destinations')
              .set({ ...baseValues(destination), updated_at: sql<Date>`now()` })
              .where('id', '=', id)
              .execute()
            await updateDetail(trx, id, destination.detail, newSecret)
            return { id, ...destination }
          }),
      ),

    /** `inUse` when deliveries or synced articles still reference the destination. */
    delete: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, async () => {
        const result = await write(db, (trx) =>
          trx.deleteFrom('production_destinations').where('id', '=', id).executeTakeFirst(),
        )
        return result.numDeletedRows === 0n ? 'notFound' : 'deleted'
      }),
  }
}

const baseValues = (destination: DestinationWrite) => ({
  supplier_id: destination.supplierId,
  channel: destination.detail.channel,
  label: destination.label,
  enabled: destination.enabled,
  notification_email: destination.notificationEmail,
  notification_name: destination.notificationName,
})

async function insertDetail(
  trx: Transaction<Tables>,
  id: number,
  detail: DestinationDetail,
  secret: string,
) {
  if (detail.channel === 'SFTP') {
    await trx
      .insertInto('production_destination_sftp')
      .values({ id, ...sftpValues(detail), password: secret })
      .execute()
  } else {
    await trx
      .insertInto('production_destination_spod')
      .values({
        id,
        environment: detail.environment,
        timeout_seconds: detail.timeoutSeconds,
        access_token: secret,
      })
      .execute()
  }
}

async function updateDetail(
  trx: Transaction<Tables>,
  id: number,
  detail: DestinationDetail,
  newSecret: string | null,
) {
  if (detail.channel === 'SFTP') {
    await trx
      .updateTable('production_destination_sftp')
      .set({ ...sftpValues(detail), ...(newSecret === null ? {} : { password: newSecret }) })
      .where('id', '=', id)
      .execute()
  } else {
    await trx
      .updateTable('production_destination_spod')
      .set({
        environment: detail.environment,
        timeout_seconds: detail.timeoutSeconds,
        ...(newSecret === null ? {} : { access_token: newSecret }),
      })
      .where('id', '=', id)
      .execute()
  }
}

const sftpValues = (detail: Extract<DestinationDetail, { channel: 'SFTP' }>) => ({
  host: detail.host,
  port: detail.port,
  username: detail.username,
  host_key_fingerprint: detail.hostKeyFingerprint,
  remote_path: detail.remotePath,
  timeout_seconds: detail.timeoutSeconds,
})

interface JoinedRow {
  id: number
  supplier_id: number
  channel: string
  label: string
  enabled: boolean
  notification_email: string | null
  notification_name: string | null
  host: string | null
  port: number | null
  username: string | null
  host_key_fingerprint: string | null
  remote_path: string | null
  sftp_timeout_seconds: number | null
  environment: string | null
  spod_timeout_seconds: number | null
}

function toStored(row: JoinedRow): StoredDestination {
  return {
    id: row.id,
    supplierId: row.supplier_id,
    label: row.label,
    enabled: row.enabled,
    notificationEmail: row.notification_email,
    notificationName: row.notification_name,
    detail:
      row.channel === 'SFTP'
        ? {
            channel: 'SFTP',
            host: detailValue(row.host),
            port: detailValue(row.port),
            username: detailValue(row.username),
            hostKeyFingerprint: detailValue(row.host_key_fingerprint),
            remotePath: detailValue(row.remote_path),
            timeoutSeconds: detailValue(row.sftp_timeout_seconds),
          }
        : {
            channel: 'SPOD',
            environment: spodEnvironmentOfStoredValue(detailValue(row.environment)),
            timeoutSeconds: detailValue(row.spod_timeout_seconds),
          },
  }
}

/**
 * A column of the left-joined detail row. The composite foreign key guarantees the detail row of
 * the base row's channel exists, so a missing one is a broken database.
 */
function detailValue<T>(value: T | null): T {
  if (value === null) throw new Error('Production destination row without its detail row')
  return value
}

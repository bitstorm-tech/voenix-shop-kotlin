import type { SpodCatalogSync, SpodSyncReport } from '@/modules/article'
import type { SpodEnvironment } from '@/modules/spod'
import {
  buildValidationErrors,
  invalid,
  notFound,
  ok,
  type OperationResult,
} from '@/platform/operation'
import type {
  DestinationDetail,
  DestinationRepository,
  DestinationWrite,
  DestinationWriteResult,
  StoredDestination,
} from './repository'

/**
 * The validated create or replace body, as `routes.ts`'s schema produces it: trimmed, defaults
 * applied, the block of its channel as `detail`, and the channel's secret apart from everything
 * else. `secret` is `null` when the body sets none (blank counts as none); a non-blank secret is
 * kept exactly as typed.
 */
export interface DestinationInput extends DestinationWrite {
  secret: string | null
}

/** The admin answer: the shared fields plus the block of its channel, the other block `null`. */
export interface DestinationResponse {
  id: number
  supplierId: number
  channel: DestinationDetail['channel']
  label: string
  enabled: boolean
  notificationEmail: string | null
  notificationName: string | null
  sftp: {
    host: string
    port: number
    username: string
    hostKeyFingerprint: string
    remotePath: string
    timeoutSeconds: number
  } | null
  spod: { environment: SpodEnvironment; timeoutSeconds: number } | null
}

/** A sync request: the run's report (also for a failed run), or why there was no run. */
export type DestinationSyncResult =
  | { kind: 'reported'; report: SpodSyncReport }
  | { kind: 'notFound' }
  | { kind: 'notSyncable' }
  | { kind: 'busy' }

export type DestinationService = ReturnType<typeof createDestinationService>

const UNCONFIGURED_SPOD =
  'This deployment has no production.spod configuration, so no SPOD destination can be stored'

/**
 * The admin destination use cases. `spodConfigured` is the write side of the startup check: a
 * deployment without the SPOD settings would refuse to start once a SPOD destination exists, so
 * none may be stored, enabled or not.
 */
export function createDestinationService(
  repository: DestinationRepository,
  spodConfigured: boolean,
  spodCatalogSync: Pick<SpodCatalogSync, 'sync'>,
) {
  const refusedSpod = (input: DestinationInput) =>
    !spodConfigured && input.detail.channel === 'SPOD'

  return {
    list: async (): Promise<DestinationResponse[]> => (await repository.list()).map(toResponse),

    get: async (id: number): Promise<OperationResult<DestinationResponse>> => {
      const stored = await repository.find(id)
      return stored === undefined ? notFound : ok(toResponse(stored))
    },

    create: async (input: DestinationInput): Promise<OperationResult<DestinationResponse>> => {
      const errors = buildValidationErrors((add) => {
        if (refusedSpod(input)) add('channel', UNCONFIGURED_SPOD)
        if (input.secret === null) {
          if (input.detail.channel === 'SPOD') add('spod.accessToken', 'AccessToken is required')
          else add('sftp.password', 'Password is required')
        }
      })
      if (Object.keys(errors).length > 0 || input.secret === null) return invalid(errors)
      return toResult(await repository.insert(writeOf(input), input.secret))
    },

    update: async (
      id: number,
      input: DestinationInput,
    ): Promise<OperationResult<DestinationResponse>> => {
      if (refusedSpod(input)) return invalid({ channel: [UNCONFIGURED_SPOD] })
      return toResult(await repository.update(id, writeOf(input), input.secret))
    },

    delete: repository.delete,

    /**
     * Reads the destination and hands its catalog source to the article module's sync, which runs
     * to the end before this returns (SPOD sync decision 5). Opens no transaction of its own.
     */
    syncArticles: async (id: number): Promise<DestinationSyncResult> => {
      const source = await repository.catalogSource(id)
      if (source === 'notFound') return { kind: 'notFound' }
      if (source === 'notSyncable') return { kind: 'notSyncable' }
      return spodCatalogSync.sync(source)
    },
  }
}

const writeOf = (input: DestinationInput): DestinationWrite => ({
  supplierId: input.supplierId,
  label: input.label,
  enabled: input.enabled,
  notificationEmail: input.notificationEmail,
  notificationName: input.notificationName,
  detail: input.detail,
})

function toResult(result: DestinationWriteResult): OperationResult<DestinationResponse> {
  switch (result) {
    case 'notFound':
      return notFound
    case 'supplierNotFound':
      return invalid({ supplierId: ['Supplier not found'] })
    case 'enabledSpodExists':
      return invalid({
        channel: ['Supplier already has an enabled SPOD destination; disable it first'],
      })
    case 'channelImmutable':
      return invalid({ channel: ['Channel cannot be changed after creation'] })
    case 'supplierImmutable':
      return invalid({ supplierId: ['Supplier cannot be changed after creation'] })
    default:
      return ok(toResponse(result))
  }
}

function toResponse(stored: StoredDestination): DestinationResponse {
  const { detail } = stored
  return {
    id: stored.id,
    supplierId: stored.supplierId,
    channel: detail.channel,
    label: stored.label,
    enabled: stored.enabled,
    notificationEmail: stored.notificationEmail,
    notificationName: stored.notificationName,
    sftp:
      detail.channel === 'SFTP'
        ? {
            host: detail.host,
            port: detail.port,
            username: detail.username,
            hostKeyFingerprint: detail.hostKeyFingerprint,
            remotePath: detail.remotePath,
            timeoutSeconds: detail.timeoutSeconds,
          }
        : null,
    spod:
      detail.channel === 'SPOD'
        ? { environment: detail.environment, timeoutSeconds: detail.timeoutSeconds }
        : null,
  }
}

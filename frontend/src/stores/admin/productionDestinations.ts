import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { PrintFrameDto } from '@/stores/shop/catalog'

const DESTINATIONS_PATH = '/api/admin/production/destinations'

/** How a supplier's production jobs leave this shop. */
export type ProductionChannel = 'SFTP' | 'SPOD'

/** Which installation of the print-on-demand partner a SPOD destination talks to. */
export type SpodEnvironment = 'STAGING' | 'PRODUCTION'

export const PRODUCTION_CHANNELS: readonly ProductionChannel[] = ['SFTP', 'SPOD']
export const SPOD_ENVIRONMENTS: readonly SpodEnvironment[] = ['STAGING', 'PRODUCTION']

/** The SFTP account of a destination. The password is never part of a response. */
export interface SftpDestinationDetailsDto {
  host: string
  port: number
  username: string
  hostKeyFingerprint: string
  remotePath: string
  timeoutSeconds: number
}

/** The SPOD account of a destination. The access token is never part of a response. */
export interface SpodDestinationDetailsDto {
  environment: SpodEnvironment
  timeoutSeconds: number
}

/**
 * One production destination as list, detail, create, and update all answer it
 * (`docs/dev/backend/packages/production-package.md`).
 *
 * Exactly one detail block is present, and it is the one the `channel` names — the same rule the
 * request body follows. Neither block carries its secret: the SFTP password and the SPOD access
 * token are write-only, so nothing this store holds can render one back.
 */
export interface AdminProductionDestinationDto {
  id: number
  supplierId: number
  channel: ProductionChannel
  label: string
  enabled: boolean
  notificationEmail: string | null
  notificationName: string | null
  sftp?: SftpDestinationDetailsDto | null
  spod?: SpodDestinationDetailsDto | null
}

/**
 * The SFTP block of a write.
 *
 * `password` is the write-only half: a create must carry it, and an update that omits it — or
 * sends it blank — keeps the stored one.
 */
export interface SftpDestinationInputDto {
  host: string
  port?: number | null
  username: string
  password?: string | null
  hostKeyFingerprint: string
  remotePath?: string | null
  timeoutSeconds: number
}

/** The SPOD block of a write. `accessToken` is write-only in exactly the way the password is. */
export interface SpodDestinationInputDto {
  environment: SpodEnvironment
  accessToken?: string | null
  timeoutSeconds: number
}

/**
 * The shared create/update body.
 *
 * The channel decides which detail block belongs to it: an `SFTP` destination sends `sftp` and no
 * `spod`, a `SPOD` destination the other way round. Sending the wrong one, or none, is a field
 * error on `channel` — the channel is what makes the rest of the body right or wrong.
 */
export interface SaveProductionDestinationRequest {
  supplierId: number
  channel: ProductionChannel
  label: string
  enabled?: boolean
  notificationEmail?: string | null
  notificationName?: string | null
  sftp?: SftpDestinationInputDto
  spod?: SpodDestinationInputDto
}

/** `COMPLETED` means the run read the catalog to the end; only such a run may deactivate anything. */
export type SpodSyncStatus = 'COMPLETED' | 'FAILED'

/** One article of a report's lists. `articleId` is `null` for one that never became a row. */
export interface SpodSyncLine {
  articleId: number | null
  spodArticleId: string
  name: string
  /** The SPOD hotspot the sync chose as the print area, `null` when it could not measure one. */
  hotspot: string | null
  /** The print frame the sync measured from the hotspot preview, `null` together with `hotspot`. */
  printFrame: PrintFrameDto | null
  variantsCreated: number
  variantsUpdated: number
  variantsDeactivated: number
}

/**
 * Everything a run reports short of failing. The backend's `SpodSyncWarningCode` is a closed
 * list, but the screen only prints the code it was given, so a code this frontend has never heard
 * of shows up as itself instead of forcing a release here. The one code the screen singles out is
 * `CALIBRATION_FAILED`: such an article has no print frame and cannot be activated.
 */
export interface SpodSyncWarning {
  code: string
  spodArticleId: string | null
  detail: string
}

/**
 * What one sync run did. Every article the run saw is in exactly one of the five lists, so
 * "nothing changed" is a visible answer: a second identical run reports everything as `unchanged`.
 *
 * A `FAILED` status means the catalog could not be read to the end. Nothing was written then, and
 * `failure` carries the bounded reason.
 */
export interface SpodSyncReport {
  destinationId: number
  supplierId: number
  environment: SpodEnvironment
  status: SpodSyncStatus
  /** The bounded reason of a `FAILED` run (`SpodError` on the backend), printed as it arrives. */
  failure: string | null
  startedAt: string
  finishedAt: string
  fetchedArticles: number
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
}

/**
 * A running sync as far as it got: the report's lists so far, and where it is. `LISTING` reads the
 * partner's catalog, `ARTICLES` works through it one article at a time (`currentArticle` is the
 * title in work), `SWEEPING` deactivates what the partner no longer lists.
 */
export interface SpodSyncProgress {
  phase: 'LISTING' | 'ARTICLES' | 'SWEEPING'
  startedAt: string
  fetchedArticles: number
  processedArticles: number
  currentArticle: string | null
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
}

/**
 * What `…/sync-articles` answers, to the start and to every poll: whether a run still works, what
 * it has done so far, and the report of the last run that finished since the backend started.
 */
export interface SpodSyncState {
  running: boolean
  progress: SpodSyncProgress | null
  report: SpodSyncReport | null
}

/**
 * Whether the partner calls this deployment for one event: `WRONG_URL` means subscribed, but to
 * another address (an old secret or another host). `otherHosts` names those addresses' hosts.
 */
export interface SpodWebhookStatus {
  eventType: string
  state: 'ACTIVE' | 'MISSING' | 'WRONG_URL'
  otherHosts: string[]
}

/** The codes of the webhook routes, in the words the admin screen shows. */
const WEBHOOK_MESSAGES: Record<string, string> = {
  WEBHOOK_URL_NOT_PUBLIC:
    'This shop has no public HTTPS address for Spreadconnect; set PRODUCTION_SPOD_WEBHOOK_BASE_URL (locally an ngrok URL).',
  REFUSED: 'Spreadconnect refused the call; check the access token of this destination.',
  RATE_LIMITED: 'Spreadconnect rate limit reached; try again in a minute.',
  PROVIDER_UNAVAILABLE: 'Spreadconnect could not be reached.',
  PROVIDER_ANSWER_UNREADABLE: 'Spreadconnect answered something this shop cannot read.',
}

/** How often a running sync is polled. A run takes seconds to many minutes. */
const SYNC_POLL_INTERVAL_MS = 2000

/** The two `409` codes of the sync route, in the words the admin screen shows. */
const SYNC_CONFLICT_MESSAGES: Record<string, string> = {
  SYNC_RUNNING: 'A sync is already running for this destination.',
  CHANNEL_WITHOUT_CATALOG: 'Only Spreadconnect destinations can be synced.',
}

export class DestinationNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DestinationNotFoundError'
  }
}

/**
 * The one conflict these routes have: a destination that jobs still reference cannot be deleted.
 * Disabling it is the way out, which is why the message says so rather than offering a retry.
 */
export class DestinationInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DestinationInUseError'
  }
}

/**
 * A `400` from a destination route, with the messages the backend put on the fields of the body.
 *
 * The keys are JSON paths of the submitted body: `supplierId`, `channel`, `label`,
 * `notificationEmail`, and the detail paths `sftp.host`, `sftp.timeoutSeconds`,
 * `spod.environment`, `spod.accessToken`. Two rules that are not about a single input report on
 * `channel` as well: a detail block that does not match the channel, and the second enabled SPOD
 * destination of one supplier.
 */
export class InvalidDestinationRequestError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors = {}) {
    super(message)
    this.name = 'InvalidDestinationRequestError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

export const useAdminProductionDestinationsStore = defineStore(
  'admin-production-destinations',
  () => {
    const destinations = ref<AdminProductionDestinationDto[]>([])
    const isLoading = shallowRef(false)
    const error = shallowRef<string | null>(null)
    /** The destinations whose sync is still running. A sync runs per destination. */
    const syncingDestinationIds = ref<number[]>([])
    /** The report of the last finished sync of a destination, kept until the page is left. */
    const syncReports = ref<Record<number, SpodSyncReport>>({})
    /** What a destination's running sync has done so far, from its latest poll. */
    const syncProgresses = ref<Record<number, SpodSyncProgress>>({})

    function sortDestinations(items: AdminProductionDestinationDto[]) {
      return [...items].sort(
        (left, right) =>
          left.supplierId - right.supplierId ||
          left.label.localeCompare(right.label) ||
          left.id - right.id,
      )
    }

    function syncDestination(destination: AdminProductionDestinationDto) {
      const index = destinations.value.findIndex((item) => item.id === destination.id)
      if (index === -1) {
        destinations.value = sortDestinations([...destinations.value, destination])
        return
      }

      destinations.value[index] = destination
    }

    function removeDestination(id: number) {
      destinations.value = destinations.value.filter((destination) => destination.id !== id)
    }

    /**
     * `400 Validation failed` with field errors, `404` for an unknown id, and — on the delete
     * alone — `409` for a destination jobs still point at.
     */
    function toDestinationError(err: unknown) {
      const message = err instanceof Error ? err.message : 'Unknown error'

      if (!(err instanceof ApiError)) {
        return new Error(message)
      }

      if (err.status === 400) {
        return new InvalidDestinationRequestError(message, err.fieldErrors)
      }

      if (err.status === 404) {
        return new DestinationNotFoundError(message)
      }

      if (err.status === 409) {
        return new DestinationInUseError(message)
      }

      return new Error(message)
    }

    async function fetchDestinations() {
      if (isLoading.value) {
        return
      }

      isLoading.value = true
      error.value = null

      try {
        const items = await fetchJson<AdminProductionDestinationDto[]>(DESTINATIONS_PATH)
        destinations.value = sortDestinations(items)
      } catch (err) {
        error.value = err instanceof Error ? err.message : 'Unknown error'
      } finally {
        isLoading.value = false
      }
    }

    async function fetchDestination(id: number): Promise<AdminProductionDestinationDto> {
      try {
        const destination = await fetchJson<AdminProductionDestinationDto>(
          `${DESTINATIONS_PATH}/${id}`,
        )
        syncDestination(destination)
        return destination
      } catch (err) {
        throw toDestinationError(err)
      }
    }

    async function createDestination(
      payload: SaveProductionDestinationRequest,
    ): Promise<AdminProductionDestinationDto> {
      try {
        const destination = await fetchJson<AdminProductionDestinationDto>(DESTINATIONS_PATH, {
          method: 'POST',
          body: payload,
        })
        syncDestination(destination)
        return destination
      } catch (err) {
        throw toDestinationError(err)
      }
    }

    async function updateDestination(
      id: number,
      payload: SaveProductionDestinationRequest,
    ): Promise<AdminProductionDestinationDto> {
      try {
        const destination = await fetchJson<AdminProductionDestinationDto>(
          `${DESTINATIONS_PATH}/${id}`,
          {
            method: 'PUT',
            body: payload,
          },
        )
        syncDestination(destination)
        return destination
      } catch (err) {
        throw toDestinationError(err)
      }
    }

    async function deleteDestination(id: number): Promise<void> {
      try {
        await fetchJson<void>(`${DESTINATIONS_PATH}/${id}`, {
          method: 'DELETE',
          responseType: 'void',
        })
      } catch (err) {
        throw toDestinationError(err)
      }

      removeDestination(id)
    }

    function isSyncing(id: number): boolean {
      return syncingDestinationIds.value.includes(id)
    }

    function syncReport(id: number): SpodSyncReport | null {
      return syncReports.value[id] ?? null
    }

    function syncProgress(id: number): SpodSyncProgress | null {
      return syncProgresses.value[id] ?? null
    }

    function syncPath(id: number) {
      return `${DESTINATIONS_PATH}/${id}/sync-articles`
    }

    function setProgress(id: number, progress: SpodSyncProgress | null) {
      const others = { ...syncProgresses.value }
      delete others[id]
      syncProgresses.value = progress ? { ...others, [id]: progress } : others
    }

    /** Polls a run until it ends, showing its progress on the way, and keeps its report. */
    async function followSync(id: number, state: SpodSyncState): Promise<SpodSyncReport | null> {
      try {
        while (state.running) {
          setProgress(id, state.progress)
          await new Promise((resolve) => setTimeout(resolve, SYNC_POLL_INTERVAL_MS))
          state = await fetchJson<SpodSyncState>(syncPath(id))
        }
      } finally {
        setProgress(id, null)
      }
      if (state.report) {
        syncReports.value = { ...syncReports.value, [id]: state.report }
      }
      return state.report
    }

    /**
     * Picks up what the backend knows about the destination's sync when the page opens: a run still
     * working (started before a reload, or by another admin) is followed, a finished one shows its
     * report. A failed read only means no report is shown, so it is not an error here.
     */
    async function loadSyncState(id: number): Promise<void> {
      if (isSyncing(id)) {
        return
      }
      syncingDestinationIds.value = [...syncingDestinationIds.value, id]
      try {
        await followSync(id, await fetchJson<SpodSyncState>(syncPath(id)))
      } catch {
        // Nothing to show; the sync button still works.
      } finally {
        syncingDestinationIds.value = syncingDestinationIds.value.filter((item) => item !== id)
      }
    }

    /**
     * Reads the destination's backoffice catalog into the shop's SPOD articles and keeps what the run
     * did. The backend runs the sync in the background, so this starts it and polls until it ends.
     *
     * A `FAILED` report is a normal answer and is stored like any other; only the `409` of a
     * destination that is already syncing, of one without a catalog, and a run that broke without a
     * report come back as an error.
     */
    async function syncArticles(id: number): Promise<SpodSyncReport> {
      syncingDestinationIds.value = [...syncingDestinationIds.value, id]

      // The report of the previous run says nothing about the one that just started, and a run that
      // ends in a `409` writes none, so the stale one goes now rather than lingering under the
      // spinner.
      const remaining = { ...syncReports.value }
      delete remaining[id]
      syncReports.value = remaining

      try {
        const started = await fetchJson<SpodSyncState>(syncPath(id), { method: 'POST' })
        const report = await followSync(id, started)
        if (!report) {
          throw new Error('The sync stopped without a report; see the backend log.')
        }
        return report
      } catch (err) {
        if (err instanceof ApiError && err.status === 409) {
          throw new Error(SYNC_CONFLICT_MESSAGES[err.code ?? ''] ?? err.message)
        }

        throw toDestinationError(err)
      } finally {
        syncingDestinationIds.value = syncingDestinationIds.value.filter((item) => item !== id)
      }
    }

    async function webhookCall(id: number, method: 'GET' | 'POST'): Promise<SpodWebhookStatus[]> {
      try {
        return await fetchJson<SpodWebhookStatus[]>(`${DESTINATIONS_PATH}/${id}/webhooks`, {
          method,
        })
      } catch (err) {
        const code = err instanceof ApiError ? (err.code ?? '') : ''
        throw new Error(
          WEBHOOK_MESSAGES[code] ?? (err instanceof Error ? err.message : 'Unknown error'),
        )
      }
    }

    /** Asks Spreadconnect whether it calls this deployment for every event the shop acts on. */
    function webhookStatus(id: number): Promise<SpodWebhookStatus[]> {
      return webhookCall(id, 'GET')
    }

    /** Subscribes the missing events and replaces stale subscriptions; answers the new status. */
    function registerWebhooks(id: number): Promise<SpodWebhookStatus[]> {
      return webhookCall(id, 'POST')
    }

    return {
      destinations,
      isLoading,
      error,
      fetchDestinations,
      fetchDestination,
      createDestination,
      updateDestination,
      deleteDestination,
      isSyncing,
      syncReport,
      syncProgress,
      syncArticles,
      loadSyncState,
      webhookStatus,
      registerWebhooks,
    }
  },
)

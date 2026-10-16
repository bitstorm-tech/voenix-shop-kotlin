import { beforeEach, describe, expect, spyOn, test } from 'bun:test'
import { sql } from 'kysely'
import { createEmailModule, type QueuedEmailKind } from '@/modules/email'
import { createLogger } from '@/platform/logging'
import { testDatabase } from '@test-support/database'
import { createFindingRepository, type Finding } from './repository'
import { createOperationsWorker } from './worker'

const database = testDatabase()

const SINCE = new Date('2026-10-16T10:00:00.000Z')

const stalledJob = (attemptCount: number, lastErrorCode: string | null): Finding => ({
  kind: 'JOB_GENERATION_STALLED',
  subject: { type: 'JOB', id: 3 },
  orderId: 42,
  since: SINCE,
  attemptCount,
  lastErrorCode,
})

const unresolvedPayment: Finding = {
  kind: 'PAYMENT_UNRESOLVED',
  subject: { type: 'PAYMENT', id: 9 },
  orderId: 41,
  since: SINCE,
  attemptCount: null,
  lastErrorCode: null,
}

const ALERT_EMAIL = 'ops@voenix.test'
const logger = createLogger('operations-worker-test')

const stalledEmail = (id: number, emailKind: QueuedEmailKind): Finding => ({
  kind: 'EMAIL_STALLED',
  subject: { type: 'EMAIL', id },
  orderId: null,
  since: SINCE,
  attemptCount: 3,
  lastErrorCode: 'SWEEGO_UNAVAILABLE',
  emailKind,
})

const spodJob = (kind: 'SPOD_QUARANTINED' | 'SPOD_REMOTE_ATTENTION', id: number): Finding => ({
  kind,
  subject: { type: 'JOB', id },
  orderId: 43,
  since: SINCE,
  attemptCount: null,
  lastErrorCode: null,
})

/**
 * A worker over one source whose answer the test sets before each scan; `reconcilePayments` runs
 * as the scan's first step. It alerts `alertEmail` through the real outbox and logs to `logger`.
 */
function scripted(
  reconcilePayments: (now: Date) => Promise<void> = () => Promise.resolve(),
  alertEmail: string | null = ALERT_EMAIL,
) {
  const answer: { findings: Finding[]; scans: number; readAt: Date | null } = {
    findings: [],
    scans: 0,
    readAt: null,
  }
  const worker = createOperationsWorker({
    reconcilePayments,
    sources: [
      {
        findings: (now) => {
          answer.scans += 1
          answer.readAt = now
          return Promise.resolve(answer.findings)
        },
      },
    ],
    repository: createFindingRepository(database.db),
    outbox: createEmailModule({
      db: database.db,
      settings: {
        enabled: false,
        pollIntervalMinutes: 5,
        apiKey: '',
        fromEmail: '',
        fromName: 'Voenix',
      },
      fetch: () => Promise.reject(new Error('No mail is sent in this test')),
    }).outbox,
    alertEmail,
    frontendBaseUrl: 'https://shop.example',
    logger,
  })
  return { worker, answer }
}

const rows = () =>
  database.db
    .selectFrom('operations_findings')
    .select([
      'id',
      'kind',
      'subject_type',
      'subject_id',
      'order_id',
      'since',
      'attempt_count',
      'last_error_code',
      'first_seen_at',
      'last_seen_at',
      'alerted_at',
      'resolved_at',
    ])
    .orderBy('id')
    .execute()

const mails = async () =>
  (await database.db.selectFrom('email_jobs').select('payload').orderBy('id').execute()).map(
    (row) => row.payload,
  )

beforeEach(async () => {
  await sql`TRUNCATE operations_findings, email_jobs RESTART IDENTITY`.execute(database.db)
})

describe('the operations scan', () => {
  test('inserts a new finding and refreshes a known one, keeping first_seen_at', async () => {
    const { worker, answer } = scripted()
    answer.findings = [stalledJob(1, null), unresolvedPayment]
    await worker.runOnce()
    const [job, payment] = await rows()
    expect(job).toMatchObject({
      kind: 'JOB_GENERATION_STALLED',
      subject_type: 'JOB',
      subject_id: 3,
      order_id: 42,
      since: SINCE,
      attempt_count: 1,
      last_error_code: null,
      resolved_at: null,
    })
    expect(payment).toMatchObject({ kind: 'PAYMENT_UNRESOLVED', subject_id: 9, resolved_at: null })

    answer.findings = [stalledJob(4, 'MISSING_IMAGE'), unresolvedPayment]
    await worker.runOnce()
    const after = await rows()
    expect(after).toHaveLength(2)
    expect(after[0]).toMatchObject({
      id: job?.id,
      attempt_count: 4,
      last_error_code: 'MISSING_IMAGE',
      first_seen_at: job?.first_seen_at,
      resolved_at: null,
    })
    expect(after[0]?.last_seen_at.getTime()).toBeGreaterThan(job?.last_seen_at.getTime() ?? 0)
  })

  test('resolves a finding the scan no longer reports, and a reappearing one is a new row', async () => {
    const { worker, answer } = scripted()
    answer.findings = [stalledJob(1, null), unresolvedPayment]
    await worker.runOnce()

    answer.findings = [unresolvedPayment]
    await worker.runOnce()
    const [resolved, open] = await rows()
    expect(resolved?.resolved_at).not.toBeNull()
    expect(open?.resolved_at).toBeNull()

    answer.findings = []
    await worker.runOnce()
    expect((await rows()).every((row) => row.resolved_at !== null)).toBe(true)
    const resolvedAt = (await rows())[0]?.resolved_at

    answer.findings = [stalledJob(2, null)]
    await worker.runOnce()
    const after = await rows()
    expect(after).toHaveLength(3)
    expect(after[0]?.resolved_at).toEqual(resolvedAt ?? null)
    expect(after[2]).toMatchObject({
      kind: 'JOB_GENERATION_STALLED',
      subject_id: 3,
      resolved_at: null,
    })
    expect(after[2]?.first_seen_at.getTime()).toBeGreaterThan(
      after[0]?.first_seen_at.getTime() ?? 0,
    )
  })

  test('reconciles the payments first: a payment it resolves is not recorded in the same scan', async () => {
    const steps: string[] = []
    let reconciledAt: Date | null = null
    const { worker, answer } = scripted((now) => {
      steps.push(`reconcile, then ${String(answer.scans)} reads`)
      reconciledAt = now
      // The reconciliation pays the order, so its payment is no longer reported.
      answer.findings = []
      return Promise.resolve()
    })
    answer.findings = [unresolvedPayment]
    await worker.runOnce()
    expect(steps).toEqual(['reconcile, then 0 reads'])
    expect(answer.scans).toBe(1)
    expect(answer.readAt).toBe(reconciledAt)
    expect(await rows()).toEqual([])
  })

  test('start scans at once and stop ends the loop without waiting for the interval', async () => {
    const { worker, answer } = scripted()
    answer.findings = [unresolvedPayment]

    worker.start()
    expect(() => {
      worker.start()
    }).toThrow('already started')
    for (let tries = 0; tries < 100 && (await rows()).length === 0; tries++) await Bun.sleep(10)
    await worker.stop()

    expect(answer.scans).toBe(1)
    expect(await rows()).toMatchObject([{ kind: 'PAYMENT_UNRESOLVED', resolved_at: null }])
  })
})

describe('the operations alert', () => {
  test('several new findings are one mail in the scan, and a finding seen again is not mailed again', async () => {
    const { worker, answer } = scripted()
    answer.findings = [stalledJob(1, null), unresolvedPayment]
    await worker.runOnce()
    const [mail, ...others] = await mails()
    expect(others).toEqual([])
    expect(mail).toMatchObject({
      kind: 'OPERATIONS_ALERT',
      recipient: ALERT_EMAIL,
      subject: 'Betrieb: 2 neue Befunde',
    })
    expect(mail?.text).toContain('Produktions-PDF wird nicht erzeugt')
    expect(mail?.text).toContain('Zahlung seit über einer Stunde ohne Ergebnis')
    expect(mail?.text).toContain('https://shop.example/admin/orders/42')
    const [job, payment] = await rows()
    expect(job?.alerted_at).toEqual(job?.first_seen_at ?? null)
    expect(payment?.alerted_at).toEqual(payment?.first_seen_at ?? null)

    answer.findings = [stalledJob(4, 'MISSING_IMAGE'), unresolvedPayment]
    await worker.runOnce()
    answer.findings = [unresolvedPayment]
    await worker.runOnce()
    expect(await mails()).toHaveLength(1)
    expect((await rows())[1]?.alerted_at).toEqual(payment?.alerted_at ?? null)
  })

  test('the two SPOD kinds and a stalled operations alert are listed only', async () => {
    const { worker, answer } = scripted()
    answer.findings = [
      spodJob('SPOD_QUARANTINED', 5),
      spodJob('SPOD_REMOTE_ATTENTION', 6),
      stalledEmail(7, 'OPERATIONS_ALERT'),
    ]
    await worker.runOnce()
    expect(await mails()).toEqual([])

    answer.findings = [...answer.findings, stalledEmail(8, 'ORDER_CONFIRMATION')]
    await worker.runOnce()
    const [mail, ...others] = await mails()
    expect(others).toEqual([])
    expect(mail?.subject).toBe('Betrieb: 1 neuer Befund')
    expect(mail?.text).toContain('Betroffen:      EMAIL 8')
    const recorded = await rows()
    expect(recorded.map((row) => [row.subject_id, row.alerted_at === null])).toEqual([
      [5, true],
      [6, true],
      [7, true],
      [8, false],
    ])
  })

  test('a finding that resolved and reappears is mailed again', async () => {
    const { worker, answer } = scripted()
    answer.findings = [stalledJob(1, null)]
    await worker.runOnce()
    answer.findings = []
    await worker.runOnce()
    answer.findings = [stalledJob(2, null)]
    await worker.runOnce()
    expect(await mails()).toHaveLength(2)
    expect((await rows()).map((row) => row.alerted_at === null)).toEqual([false, false])
  })

  test('without an address the findings are recorded unalerted and each new one is logged once', async () => {
    const error = spyOn(logger, 'error').mockImplementation(() => undefined)
    try {
      const { worker, answer } = scripted(undefined, null)
      answer.findings = [stalledJob(1, null), unresolvedPayment, spodJob('SPOD_QUARANTINED', 5)]
      await worker.runOnce()
      await worker.runOnce()
      expect(await mails()).toEqual([])
      expect((await rows()).map((row) => [row.kind, row.alerted_at])).toEqual([
        ['JOB_GENERATION_STALLED', null],
        ['PAYMENT_UNRESOLVED', null],
        ['SPOD_QUARANTINED', null],
      ])
      expect(error.mock.calls).toEqual([
        [
          { kind: 'JOB_GENERATION_STALLED', subjectType: 'JOB', subjectId: 3 },
          'An operations finding is not mailed, because no alert address is set',
        ],
        [
          { kind: 'PAYMENT_UNRESOLVED', subjectType: 'PAYMENT', subjectId: 9 },
          'An operations finding is not mailed, because no alert address is set',
        ],
      ])
    } finally {
      error.mockRestore()
    }
  })
})

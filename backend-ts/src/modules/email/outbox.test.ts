import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { testDatabase } from '@test-support/database'
import type { DeliveryResult, EmailDelivery } from './delivery/sweego'
import { emailsOfOrder, enqueue, stalledEmails } from './outbox'
import { renderPasswordReset } from './templates/password-reset'
import { renderProducerPdfNotification } from './templates/producer-pdf-notification'
import type { RenderedEmail } from './templates/layout'
import { createUserEmails, EmailDeliveryError } from './user-emails'
import { createEmailWorker } from './worker'

const database = testDatabase()

const settings = (enabled: boolean): Settings['email'] => ({
  enabled,
  pollIntervalMinutes: 1,
  apiKey: 'test-key',
  fromEmail: 'mail@voenix.shop',
  fromName: 'Voenix Shop',
})

/** The test implementation of `EmailDelivery`: records every mail and answers from a script. */
function recordingDelivery(results: DeliveryResult[] = []) {
  const sent: { email: RenderedEmail; campaignId: string | null }[] = []
  const delivery: EmailDelivery = {
    deliver: (email, campaignId) => {
      sent.push({ email, campaignId })
      return Promise.resolve(results.shift() ?? { kind: 'accepted' })
    },
  }
  return { delivery, sent }
}

const producerMail = (orderId: number) =>
  renderProducerPdfNotification({
    recipient: 'producer@example.com',
    orderId,
    fileName: `ORD-${String(orderId)}.pdf`,
    destinationLabel: 'Produktion',
    orderDate: '2026-07-16',
    itemCount: 1,
    producerName: null,
  })

const enqueueMails = (...orderIds: number[]) =>
  write(database.db, async (trx) => {
    for (const orderId of orderIds) await enqueue(trx, producerMail(orderId))
  })

const jobs = () =>
  database.db
    .selectFrom('email_jobs')
    .select(['payload', 'attempt_count as attempts', 'last_error_code as errorCode'])
    .select(sql<boolean>`sent_at IS NOT NULL`.as('sent'))
    .orderBy('id')
    .execute()

beforeEach(async () => {
  await sql`TRUNCATE email_jobs RESTART IDENTITY`.execute(database.db)
})

describe('outbox.enqueue', () => {
  test('stores the rendered payload inside the caller transaction', async () => {
    await enqueueMails(1)
    const [job] = await jobs()
    expect(job).toEqual({ payload: producerMail(1), attempts: 0, errorCode: null, sent: false })
  })

  test('stores the order id the caller passes, and none otherwise', async () => {
    await write(database.db, async (trx) => {
      await enqueue(trx, producerMail(1), { orderId: 42 })
      await enqueue(trx, producerMail(2))
    })
    const rows = await database.db
      .selectFrom('email_jobs')
      .select('order_id')
      .orderBy('id')
      .execute()
    expect(rows).toEqual([{ order_id: 42 }, { order_id: null }])
  })

  test('a failing outer transaction leaves no job', async () => {
    const failure = await write(database.db, async (trx) => {
      await enqueue(trx, producerMail(1))
      throw new Error('the business change failed')
    }).catch((error: unknown) => error)
    expect(failure).toHaveProperty('message', 'the business change failed')
    expect(await jobs()).toEqual([])
  })
})

describe('worker', () => {
  test('a disabled worker leaves jobs untouched; an enabled one sends and marks every job', async () => {
    await enqueueMails(1, 2, 3)
    const { delivery, sent } = recordingDelivery()

    await createEmailWorker({ db: database.db, settings: settings(false), delivery }).runOnce()
    expect(sent).toEqual([])
    expect((await jobs()).map((job) => job.attempts)).toEqual([0, 0, 0])

    await createEmailWorker({ db: database.db, settings: settings(true), delivery }).runOnce()
    expect(sent).toEqual([
      { email: producerMail(1), campaignId: 'voenix-email-1' },
      { email: producerMail(2), campaignId: 'voenix-email-2' },
      { email: producerMail(3), campaignId: 'voenix-email-3' },
    ])
    expect((await jobs()).map(({ sent, attempts }) => ({ sent, attempts }))).toEqual([
      { sent: true, attempts: 1 },
      { sent: true, attempts: 1 },
      { sent: true, attempts: 1 },
    ])

    await createEmailWorker({ db: database.db, settings: settings(true), delivery }).runOnce()
    expect(sent).toHaveLength(3)
  })

  test('a failure keeps the job open with its code, and the next scan retries the stored mail', async () => {
    await enqueueMails(7)
    await sql`UPDATE email_jobs SET attempt_count = 999`.execute(database.db)
    const { delivery, sent } = recordingDelivery([{ kind: 'failed', code: 'PROVIDER_HTTP_503' }])
    const worker = createEmailWorker({ db: database.db, settings: settings(true), delivery })

    await worker.runOnce()
    expect((await jobs())[0]).toMatchObject({
      sent: false,
      attempts: 1000,
      errorCode: 'PROVIDER_HTTP_503',
    })

    await worker.runOnce()
    expect((await jobs())[0]).toMatchObject({ sent: true, attempts: 1001, errorCode: null })
    expect(sent.map((entry) => entry.campaignId)).toEqual(['voenix-email-1', 'voenix-email-1'])
    expect(sent[1]?.email).toEqual(producerMail(7))
  })

  test('start scans at once and stop ends the loop without waiting for the interval', async () => {
    await enqueueMails(1)
    const { delivery, sent } = recordingDelivery()
    const worker = createEmailWorker({ db: database.db, settings: settings(true), delivery })

    worker.start()
    expect(() => {
      worker.start()
    }).toThrow('already started')
    for (let tries = 0; tries < 100 && sent.length === 0; tries++) await Bun.sleep(10)
    await worker.stop()

    expect(sent).toHaveLength(1)
    expect((await jobs())[0]?.sent).toBe(true)
  })
})

describe('outbox.findings', () => {
  const NOW = new Date('2026-10-16T12:00:00.000Z')
  const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000)

  /** One job with the given age, attempts, and state; returns its id. */
  const seedJob = async (values: {
    minutes: number
    attempts?: number
    sent?: boolean
    orderId?: number | null
  }) => {
    const { id } = await database.db
      .insertInto('email_jobs')
      .values({
        payload: JSON.stringify(producerMail(values.orderId ?? 1)),
        order_id: values.orderId ?? null,
        created_at: minutesAgo(values.minutes),
        attempt_count: values.attempts ?? 0,
        last_error_code: (values.attempts ?? 0) > 0 ? 'PROVIDER_HTTP_503' : null,
        sent_at: values.sent === true ? minutesAgo(0) : null,
      })
      .returning('id')
      .executeTakeFirstOrThrow()
    return id
  }

  test('reports an unsent mail older than an hour or failed three times, with its kind', async () => {
    const old = await seedJob({ minutes: 61, orderId: 7 })
    const failing = await seedJob({ minutes: 1, attempts: 3 })
    await seedJob({ minutes: 60, attempts: 2 })
    await seedJob({ minutes: 600, attempts: 9, sent: true })

    expect(await stalledEmails(database.db, NOW)).toEqual([
      {
        kind: 'EMAIL_STALLED',
        subject: { type: 'EMAIL', id: old },
        orderId: 7,
        since: minutesAgo(61),
        attemptCount: 0,
        lastErrorCode: null,
        emailKind: 'PRODUCER_PDF_NOTIFICATION',
      },
      {
        kind: 'EMAIL_STALLED',
        subject: { type: 'EMAIL', id: failing },
        orderId: null,
        since: minutesAgo(1),
        attemptCount: 3,
        lastErrorCode: 'PROVIDER_HTTP_503',
        emailKind: 'PRODUCER_PDF_NOTIFICATION',
      },
    ])
  })

  test('ofOrder lists the mails of one order without their content', async () => {
    const first = await seedJob({ minutes: 30, orderId: 7, sent: true })
    const second = await seedJob({ minutes: 20, orderId: 7, attempts: 1 })
    await seedJob({ minutes: 10, orderId: 8 })

    expect(await emailsOfOrder(database.db, 7)).toEqual([
      {
        emailJobId: first,
        kind: 'PRODUCER_PDF_NOTIFICATION',
        recipient: 'producer@example.com',
        attemptCount: 0,
        lastErrorCode: null,
        createdAt: minutesAgo(30),
        sentAt: minutesAgo(0),
      },
      {
        emailJobId: second,
        kind: 'PRODUCER_PDF_NOTIFICATION',
        recipient: 'producer@example.com',
        attemptCount: 1,
        lastErrorCode: 'PROVIDER_HTTP_503',
        createdAt: minutesAgo(20),
        sentAt: null,
      },
    ])
  })
})

describe('userEmails.send', () => {
  const mail = renderPasswordReset({
    recipient: 'kunde@example.com',
    resetUrl: 'https://shop.example/reset-password?token=t',
  })

  test('sends once without a campaign id and stores nothing', async () => {
    const { delivery, sent } = recordingDelivery()
    await createUserEmails(settings(true), delivery).send(mail)
    expect(sent).toEqual([{ email: mail, campaignId: null }])
    expect(await jobs()).toEqual([])
  })

  test('is a no-op while email is disabled', async () => {
    const { delivery, sent } = recordingDelivery()
    await createUserEmails(settings(false), delivery).send(mail)
    expect(sent).toEqual([])
  })

  test('a provider failure becomes the secret-free EmailDeliveryError', async () => {
    const { delivery } = recordingDelivery([{ kind: 'failed', code: 'PROVIDER_HTTP_503' }])
    const failure = await createUserEmails(settings(true), delivery)
      .send(mail)
      .catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(EmailDeliveryError)
  })
})
